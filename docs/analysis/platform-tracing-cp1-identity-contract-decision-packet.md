# CP-1 Identity Contract Decision Packet

> Дата: 2026-07-21
> Revision: **R2 (corrected & approved)**
> Original proposal branch: `feature/cp1-identity-contract-decision` @ `3c5f6ba` (merged to master by PR #16)
> Base (authoritative): `master@91065b5`
> Correction branch: `feature/cp1-identity-contract-r2-approval`
> Статус: **CP-1 APPROVED (R2)**
> Gate: **CP-1(a,b,c,d,f) APPROVED; SLICE M UNBLOCKED**
> Ограничение: production identity-код и ABI snapshot Slice M ещё не изменялись; данный пакет фиксирует контракт до реализации.
>
> Доказательная база: `docs/analysis/platform-tracing-cp1-opus-independent-review.md` (независимый ревью + R2-корректировки) и исполненный WebFlux runtime spike (§3.7.1).

## 1. Executive decision request

Комитет **принял** CP-1 как единый identity-контракт в редакции R2. Пакет не переоткрывает
решённую модель `traceId != requestId != correlationId`, не переносит domain state в API и не
меняет утверждённый CP-C2 propagation port. Ранее вынесенный вердикт `CP-1 CHANGES REQUIRED`
снят: все шесть блокирующих корректировок (B1–B6) внесены в контракт ниже.

| Checkpoint | Решение | Статус |
|---|---|---|
| CP-1(a) | canonical baggage key `platform.correlation.id` | **APPROVED** |
| CP-1(b) | `X-Correlation-ID` только как opt-in boundary bridge; baggage остаётся canonical transport; response header по умолчанию отсутствует | **APPROVED** |
| CP-1(c) | **birth-time projection** (immutable parent, child inheritance); late assignment влияет только на logical context, baggage, MDC и новые child spans | **APPROVED** |
| CP-1(d) | исправленный минимальный OTel-free public API из §3; synchronous scope; internal identity ownership §3.5; отдельный reactive application API в WebFlux §3.7 | **APPROVED** |
| CP-1(f) | fail-closed F0: весь ingress untrusted до отдельного доказанного transport verifier; hostname/header heuristics запрещены | **APPROVED** |

**Owner decision (recorded):** владелец платформы утвердил исправленную R2-архитектуру. F0 остаётся
базовой trust-моделью. Trusted gateway/s2s/message ingress добавляется только после отдельного
transport decision с authenticated evidence (см. `RG-IDENTITY-TRUST`, §8.3).

## 2. Repository baseline

- `TraceOperations` сейчас содержит только `traceContext()` и `spans()`.
- `ActiveTraceContextView.correlationId()` всегда возвращает empty в
  `DefaultActiveTraceContextView`; `requestId()` отсутствует.
- `RequestIdSupport` уже фиксирует `[A-Za-z0-9_-]`, max 128 и reject/regenerate для requestId,
  но ошибочно называет requestId correlation id в документации; выполняет **trim-and-accept**.
- `TracingMdcKeys.CORRELATION_ID = "correlation_id"` фактически хранит requestId.
- `RequestTraceContextSnapshot` содержит только старое поле `correlationId`, traceId и spanId;
  `RequestTraceContextSnapshotSupplier` читает correlationId напрямую из MDC.
- `OutboundPropagationDecision`/`OutboundPropagationHeaders` CP-C2 описывают только control
  headers и requestId. Их форма не принадлежит Slice M и остаётся неизменной.
- Servlet/WebFlux имеют разные lifecycle boundaries; WebFlux уже использует Reactor/Micrometer
  context propagation (`RemoteServiceReactorContext`, `RemoteServiceContextPropagation`,
  `spring.reactor.context-propagation=AUTO`). API-модуль не зависит от Spring, Reactor, Servlet,
  Kafka или OTel (гейт `API_MAIN_NO_OTEL_API`).
- Web-пакеты не зависят от `..core..` напрямую (гейт `WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL`); доступ
  к implementation идёт через `autoconfigure.support.*BoundarySupport`.
- Repository не содержит доказанного canonical authenticated signal для gateway, service mesh,
  RPC или Kafka ingress. Scheduler-адаптера в production нет. Поэтому утверждать какой-либо такой
  ingress trusted нельзя.

## 3. CP-1(d): exact approved public API

Все публичные типы находятся в `space.br1440.platform.tracing.api` и его подпакетах и используют
только JDK и `jakarta.annotation`. Ни один тип не содержит OTel/Spring/Reactor/Servlet/Kafka/gRPC/MDC.

> **[B1/B2 — R2]** В публичный `platform-tracing-api` **НЕ** добавляются: `RequestContextAccessor`,
> `RequestIdentityAccessor`, `RequestIdentityContext`, `RequestIdentityBinder`, `RequestIdentityScope`.
> Единый identity store — internal (§3.5). Публичное чтение — только через `ActiveTraceContextView`.
> Application API для мутации requestId отсутствует. Custom `RequestContextAccessor` bean replacement
> не поддерживается; конкурирующие платформенные identity-компоненты приводят к startup fail-fast.

### 3.1 CorrelationScope

```java
@FunctionalInterface
public interface CorrelationScope extends AutoCloseable {

    @Override
    void close();
}
```

`close()` idempotent и не бросает checked exception. Scope действует только в synchronous dynamic
extent текущего execution. Передавать его lifetime через `Publisher`, `Mono`, `Flux`,
`CompletionStage` или callback запрещено. Scope создаётся и закрывается на одном thread;
cross-thread close бросает `IllegalStateException` и не изменяет чужой execution context.

### 3.2 TraceOperations delta

```java
@Nonnull
CorrelationScope openCorrelationScope(@Nonnull String correlationId);

void withCorrelationId(@Nonnull String correlationId, @Nonnull Runnable action);

<T> T withCorrelationId(@Nonnull String correlationId,
                        @Nonnull ThrowingSupplier<T> action) throws Exception;
```

> **[B4 — R2]** Используется репозиторный `space.br1440.platform.tracing.api.util.ThrowingSupplier`
> (`T get() throws Exception`), а **не** `java.util.concurrent.Callable`. Это устраняет второй
> checked-стиль и выравнивает с `SpanExecution.callChecked` / `ManualSpanBuilder.callChecked`.

Семантика (locked, §3.3).

### 3.3 Public synchronous semantics (locked)

- `null` correlationId/action → `NullPointerException`;
- non-canonical correlationId → `IllegalArgumentException` **до** мутации context;
- programmatic values не trim'ятся и не нормализуются;
- action исполняется ровно один раз;
- exception action пробрасывается без обёртки;
- восстановление scope — в `finally`;
- nested scopes восстанавливают состояние в LIFO;
- `close()` idempotent;
- scope — same-thread; cross-thread close → `IllegalStateException` без мутации чужого execution
  context;
- возврат `Mono`/`Flux`/`CompletionStage`/иного deferred из synchronous-хелпера **не** продлевает
  lifetime scope;
- **если `TraceOperations` присутствует — correlation assignment функционален** (silent no-op
  запрещён);
- `sdk.mode=DISABLED` сохраняет identity функциональной, при этом span emission и внешняя
  baggage-propagation отключены;
- некорректная composition приводит к startup failure, а не к silent degradation в NoOp.

### 3.4 ActiveTraceContextView delta

```java
@Nonnull
Optional<String> requestId();

@Nonnull
Optional<String> correlationId();
```

`requestId()` и `correlationId()` читают **единый internal identity store** (§3.5), не MDC/static/header.
`correlationId()` перестаёт быть alias requestId и читает validated logical identity текущего execution.

### 3.5 Internal identity ownership (не входит в public ABI)

> **[B1/B2 — R2]** Ownership фиксируется, но публичного ABI не образует.

- один internal immutable per-execution identity store;
- владелец реализации — текущая implementation-плоскость `platform-tracing-core`; при последующем
  переименовании модуля store мигрирует вместе с реализацией;
- `ActiveTraceContextView` и `RequestTraceContextSnapshotSupplier` читают из **одного и того же** store;
- MDC, baggage, span attributes и outbound headers — только проекции store;
- `RequestTraceContextSnapshotSupplier` **прекращает** использовать MDC как source of truth;
- requestId ingress binding — infrastructure-only; application API для мутации requestId отсутствует;
- custom `RequestContextAccessor` bean replacement не поддерживается;
- конкурирующие платформенные identity-компоненты → fail-fast;
- текущее значение identity никогда не хранится в static/global mutable поле;
- ThreadLocal-only WebFlux storage запрещён.

Web/framework binding ownership:

- internal identity manager/store остаётся в implementation;
- `RequestIdentityBoundarySupport` принадлежит `platform-tracing-spring-boot-autoconfigure`
  (симметрично существующему `RequestIdBoundarySupport`);
- адаптеры WebMVC/WebFlux/Kafka обращаются только к autoconfigure boundary support;
- web-модули не зависят от implementation-классов напрямую (гейт `WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL`);
- CP-C2 ABI остаётся byte-for-byte неизменной.

Внутренние helper-имена — implementation detail и не попадают в public ABI snapshot.

### 3.6 RequestTraceContextSnapshot delta

```java
public record RequestTraceContextSnapshot(
        @Nullable String requestId,
        @Nullable String correlationId,
        @Nullable String traceId,
        @Nullable String spanId) {
}
```

Это intentional breaking change. Compatibility constructor, deprecated alias и dual-read старого
`correlation_id` не добавляются.

### 3.7 CP-1(d) reactive: exact WebFlux application API

> **[B3 — R2]** Runtime spike подтвердил форму API (§3.7.1). Production-сигнатуры зафиксированы
> сейчас, не в Slice M.

- Module: `platform-tracing-autoconfigure-webflux`.
- Recommended public package: `space.br1440.platform.tracing.webflux` (новый публичный application
  пакет, отдельный от internal `space.br1440.platform.tracing.autoconfigure.reactive`). Reactor-типы
  допустимы в этом модуле и остаются **вне** `platform-tracing-api`.

```java
package space.br1440.platform.tracing.webflux;

public interface ReactiveCorrelationOperations {

    <T> reactor.core.publisher.Mono<T> withCorrelationId(String correlationId,
                                                         reactor.core.publisher.Mono<T> execution);

    <T> reactor.core.publisher.Flux<T> withCorrelationId(String correlationId,
                                                         reactor.core.publisher.Flux<T> execution);
}
```

Зафиксированные свойства контракта:

- это **поддерживаемый application API** для WebFlux-приложений;
- Reactor-типы остаются вне `platform-tracing-api`;
- реализация **не** подписывается на publisher;
- возвращаемый publisher сохраняет форму `Mono`/`Flux`;
- `correlationId` и `execution` валидируются в момент вызова API;
- `null` → `NullPointerException`;
- blank/non-canonical correlationId → `IllegalArgumentException`;
- каждая подписка получает изолированное immutable значение в Reactor Context;
- привязка покрывает поддерево publisher, переданного как `execution`;
- retry/repeat/resubscription сохраняют per-subscription изоляцию;
- вложенная привязка следует Reactor Context lexical/LIFO семантике;
- `publishOn`/`subscribeOn` и параллельное исполнение используют утверждённый Micrometer context
  bridge;
- complete/error/cancel не допускают утечки identity;
- OTel `Scope` не хранится в Reactor Context и не переносится через асинхронную границу;
- поздняя привязка не перезаписывает существующий parent span;
- child spans, рождённые внутри reactive-привязки, получают correlationId.

#### 3.7.1 Runtime spike evidence

Исполненный spike (test-sources, не production):

- `platform-tracing-autoconfigure-webflux/src/test/java/.../reactive/spike/ReactiveCorrelationOperations.java`;
- `.../reactive/spike/ReactorCorrelationSupport.java`;
- `.../reactive/spike/ReactiveCorrelationOperationsSpikeTest.java`.

Команда: `./gradlew :platform-tracing-autoconfigure-webflux:test --tests "space.br1440.platform.tracing.autoconfigure.reactive.spike.*" --no-daemon` → **BUILD SUCCESSFUL**.

Подтверждено исполнением: downstream visibility, concurrent-subscriber isolation, retry/repeat,
nested LIFO, publishOn/subscribeOn, error/cancel/timeout, immutable `String` в Context (не `Scope`),
MDC/ThreadLocal restoration на worker-thread через Micrometer bridge, и birth-time projection в
child-span (`platform.correlation_id`), созданный под поздней привязкой на другом потоке.

## 4. Context ownership and lifecycle

| Mode/boundary | Storage/carrier | Writer | Reader/cleanup |
|---|---|---|---|
| Servlet | internal identity store + supported internal OTel context bridge; request attribute как транспортная деталь | Servlet ingress adapter → `RequestIdentityBoundarySupport` | `ActiveTraceContextView`/store; filter `finally` очищает проекции |
| WebFlux | immutable value в Reactor Context на subscription | WebFlux adapter / `ReactiveCorrelationOperations` | store через approved Micrometer bridge; `doFinally` — complete/error/cancel |
| Kafka/message | immutable message-processing context, новый requestId на сообщение при отсутствии | consumer adapter → boundary support | listener execution scope закрывается после success/error |
| Scheduler | **адаптера нет** — requestId/correlationId для job только из programmatic app-scope | — | — |
| Direct/imperative | internal scoped carrier поверх supported OTel Context | `TraceOperations` internal implementation | `CorrelationScope.close()` LIFO restore |
| Agent-only | no facade/accessor | none | not applicable |

Единый identity value immutable. Static `ContextKey` внутри реализации допустим только как ключ;
current request state никогда не хранится в static/global mutable памяти. ThreadLocal-only WebFlux
реализация и перенос между независимыми запросами/сообщениями запрещены.

Reactive ownership принадлежит `platform-tracing-autoconfigure-webflux`. Он биндит на subscription,
изолирует конкурентных подписчиков и очищает на completion/error/cancellation. Reactive overload в
`platform-tracing-api` не добавляется; reactive application API живёт в WebFlux-модуле (§3.7).

## 5. Disabled and no-op semantics

> **[B4/B5 — R2]** Инвариант: *если `TraceOperations` присутствует — correlation assignment функционален.*
> Формулировка «functional no-op if documented as unavailable» удалена.

- Identity — инфраструктурная забота и остаётся доступной, когда telemetry emission выключена.
- При `sdk.mode=DISABLED`: span creation остаётся NoOp; валидные correlation scopes и request
  identity access остаются функциональными и реверсивными; nested scopes работают;
  `ActiveTraceContextView` видит присвоенное значение; внешняя baggage-propagation выключена.
- При `platform.tracing.enabled=false`: error snapshot supplier остаётся зарегистрированным, но
  web tracing filters и `TraceOperations` отсутствуют; default identity пуст, если её не поставляет
  независимый approved infrastructure adapter; Slice M не добавляет молча requestId-генерацию или
  response-заголовки в полностью выключенном режиме.
- Некорректная composition → **startup fail-fast**, а не silent NoOp.
- Disabled-режим не течёт состоянием, не эмитит baggage наружу и не меняет семантику исполнения action.

## 6. CP-1(a,b): transport contract

### 6.1 Canonical keys

- Baggage: `platform.correlation.id`.
- Optional HTTP boundary bridge: `X-Correlation-ID`.
- Request identity остаётся `X-Request-Id`; в baggage не помещается.
- Trace identity остаётся W3C `traceparent` / response `X-Trace-Id`.

### 6.2 Ingress conflict matrix

| Trust/input | Result |
|---|---|
| untrusted or unverifiable ingress | strip/ignore correlation baggage and header |
| trusted, one valid source | accept source |
| trusted, baggage and header valid and equal | accept once |
| trusted, valid but unequal sources | drop correlationId and emit bounded diagnostic |
| any duplicate canonical key/header occurrence | drop correlationId; do not fall back |
| any invalid/oversized/invalidly encoded occurrence | drop correlationId; do not generate business id |

Baggage выигрывает только при отсутствии header. Конфликт не разрешается случайным порядком парсера.
Прочие валидные baggage-члены остаются неизменными.

### 6.3 Egress and response

- Trusted internal egress несёт correlationId через W3C baggage.
- `X-Correlation-ID` egress — opt-in compatibility bridge, по умолчанию выключен.
- External/untrusted egress вырезает `platform.correlation.id` и никогда не пишет header.
- Response `X-Correlation-ID` по умолчанию выключен; `X-Request-Id` и `X-Trace-Id` сохраняют контракты.
- CP-C2 типы `PlatformOutboundPropagation`, `OutboundPropagationDecision`,
  `OutboundPropagationHeaders` неизменны. Correlation baggage/header handling принадлежит
  identity sanitizer/adapters, не control-header порту.

## 7. CP-1(c): validation and span projection

### 7.1 Canonical value (locked)

- длина: 1..128 ASCII-символов;
- allowlist: `[A-Za-z0-9_-]`;
- регистр сохраняется; без нормализации;
- без trim, truncation, replacement или normalization для programmatic input;
- invalid programmatic input → исключение **до** мутации context;
- invalid transport input отбрасывается;
- correlationId **не генерируется** платформой (бизнес-владение);
- идентификаторы opaque; не содержат PII, секретов или бизнес-данных;
- UUIDv4/ULID — приемлемые стратегии генерации на стороне приложения.

> **[B6 — R2]** `RequestIdSupport.sanitizeOrNull` (trim-and-accept) **не** переиспользуется для
> canonical internal binding: binder принимает только canonical значение без trim/нормализации.
> Ingress-parsing (толерантный) и internal binding (строгий) — разные операции.

### 7.2 Projection policy: birth-time projection (locked)

> **[B5 — R2]** Название политики: **birth-time projection with immutable parent and child inheritance.**

1. Trusted ingress санитизируется до создания auto-instrumented server span.
2. Sanitized ingress correlationId может быть однократно спроецирован в атрибут `platform.correlation_id`.
3. Поздний `openCorrelationScope(B)` меняет только logical context, baggage и MDC.
4. Он никогда не перезаписывает/не удаляет существующий parent span атрибут.
5. Child spans, созданные внутри scope B, получают B при собственном рождении.
6. Две конкурентные поздние привязки к одному parent span не мутируют его и потому детерминированы
   без CAS/lock над OTel `Span`.

Canonical span attribute — **`platform.correlation_id`**. Generic baggage-проекция для ключа
`platform.correlation.id` **подавляется**; дублирующего атрибута `baggage.platform.correlation.id`
быть не должно. Прочие allowlisted baggage-записи могут продолжать использовать `baggage.*`.
Option B (serialized late writer) и Option C (reject all late scope) отклонены.

## 8. CP-1(f): trust contract

### 8.1 F0 baseline

Нет repository-доказательства authenticated gateway/mesh/RPC/Kafka trust signal. Поэтому:

- любой внешний или недоказанный ingress — untrusted;
- имена заголовков, source hostname/IP, `Forwarded`/`X-Forwarded-*`, topic name и произвольные
  request attributes — не аутентификация;
- spoofable `X-Trusted-*` заголовки запрещены;
- default sanitizer вырезает business correlation identity до создания server/consumer span;
- programmatic application scopes поддерживаются, т.к. не являются transport trust claims.

### 8.2 What is required to enable trusted ingress later

Для каждого транспорта supplemental accepted decision должно назвать:

1. canonical authenticated signal (например, verified mTLS workload identity, не сырой header);
2. verifier implementation и owning module/team;
3. источник конфигурации, ротацию/поведение при сбое;
4. точную pre-span/pre-consumer точку исполнения;
5. негативные тесты: spoof, missing verifier, verifier exception, ambiguous credentials.

До предоставления всех пяти транспорт остаётся F0/untrusted. Универсальный `Object`-carrier,
generic `Map`, shared hostname heuristic или adapter-local policy запрещены.

### 8.3 Release gates

- Trusted inbound business correlation остаётся **вне Slice M**.
- Scheduler «trusted job metadata» не заявляется: production scheduler-адаптера и verifier нет.
- `RG-IDENTITY-TRUST` — отдельный release-hardening gate (см. `docs/architecture/rg-identity-trust-release-gate.md`).
- `RG-CONTROLLED-AGENT` остаётся отдельным gate.
- Production rollout запрещён до закрытия применимых release gates.

## 9. Public ABI and configuration impact

Intentional public API changes, owned by CP-1/Slice M:

- ADD `CorrelationScope` (`platform-tracing-api`);
- ADD три метода в `TraceOperations` (`openCorrelationScope`; `withCorrelationId(String, Runnable)`;
  `withCorrelationId(String, ThrowingSupplier<T>)`) — **ThrowingSupplier, не Callable**;
- ADD `ActiveTraceContextView.requestId()`;
- REDEFINE `ActiveTraceContextView.correlationId()`;
- REPLACE `RequestTraceContextSnapshot` на 4 независимых поля;
- ADD `PlatformHeaders.X_CORRELATION_ID`;
- ADD `PlatformAttributes.PLATFORM_CORRELATION_ID = "platform.correlation_id"`;
- ADD `TracingMdcKeys.REQUEST_ID = "requestId"`;
- REDEFINE `TracingMdcKeys.CORRELATION_ID = "correlationId"`;
- ADD `ReactiveCorrelationOperations` в `platform-tracing-autoconfigure-webflux`
  (пакет `space.br1440.platform.tracing.webflux`; Reactor-типы вне `platform-tracing-api`).

**НЕ добавляются** в public ABI: `RequestContextAccessor`, `RequestIdentityAccessor`,
`RequestIdentityContext`, `RequestIdentityBinder`, `RequestIdentityScope` — все identity-ownership
типы internal (§3.5). Внутренние helper-имена не входят в ABI snapshot.

No deprecated aliases, dual-write, compatibility constructors или legacy read fallback. ABI snapshot
должен точно отражать этот список. CP-C2 символы остаются byte-for-byte неизменными.

Spring configuration намеренно минимальна:

- default trust mode — fixed fail-closed, не permissive boolean;
- boundary `X-Correlation-ID` ingress/egress/response bridges по умолчанию выключены;
- включение trusted ingress требует approved transport verifier, не только property;
- конкурирующие платформенные identity-компоненты приводят к startup failure.

Точные property-имена — implementation-owned internal configuration; фиксируются в M3-review до
binding, а не выводятся из существующих requestId-property.

## 10. Operational migration

- Log schema меняется с legacy `correlation_id` (несущего requestId) на независимые camelCase
  `requestId` и `correlationId`.
- Error snapshot и downstream error-handling mapping получают requestId; старый correlation alias
  удаляется одной скоординированной pre-production сменой.
- Dashboards, log queries, alerts и runbooks прекращают трактовать requestId как business correlationId.
- Существующее `X-Request-Id` response/forward поведение сохраняется.
- Rollback — полный revert Slice M. Partial rollback или dual-write запрещены (реинтродуцируют
  неоднозначную семантику identity).

## 11. Mandatory implementation evidence (Slice M)

| Area | Required proof |
|---|---|
| API/ABI | точный allowlist и snapshot; без framework/OTel типов в api; identity-ownership типы отсутствуют в ABI; CP-C2 неизменны |
| Synchronous | nested/idempotent scopes, exception restore, invalid/null, same-thread, cross-thread close throws |
| Reactive | `ReactiveCorrelationOperations`: `Mono.defer`, repeat/resubscribe, два конкурентных подписчика, publishOn/subscribeOn, nested, complete/error/cancel cleanup, no Scope across async |
| Servlet | request attribute lifetime, exception/async cleanup, no MDC source-of-truth |
| Message/scheduler | новый requestId на execution, redelivery/retry, no cross-message leakage |
| Projection | ingress attr однократно; late parent unchanged; child span получает scoped correlationId; concurrent same-parent детерминированы; generic baggage-проекция ключа подавлена |
| Trust | raw/spoofed/duplicate/invalid ingress вырезан до span; verifier absence/failure fail-closed |
| Egress | только trusted baggage; external destination stripped; optional header default off |
| Disabled | identity функциональна; spans NoOp; no outbound leakage; invalid composition fail-fast |
| Agent | packaged Spring+Agent context visibility; ровно один sanitizer и projection owner; no second SDK |
| Spring topology | webmvc-only, webflux-only, оба starters; конкурирующие identity-компоненты fail-fast |

## 12. Committee resolution (recorded)

```text
CP-1 APPROVED
CP-1 REVISION: R2
Revision reviewed: 3c5f6ba86fec740ca9005bd14343ce84175ffac3
Correction base: master@91065b5

APPROVED:
  CP-1(a) — platform.correlation.id
  CP-1(b) — opt-in X-Correlation-ID boundary bridge
  CP-1(c) — birth-time projection with immutable parent
  CP-1(d) — corrected minimal API and internal ownership model
  CP-1(f) — F0 fail-closed

CP-1(d) locked contract:
  - public API adds only: CorrelationScope; TraceOperations.openCorrelationScope/withCorrelationId
    (ThrowingSupplier, not Callable); ActiveTraceContextView.requestId()/correlationId();
    four-field RequestTraceContextSnapshot; PlatformHeaders.X_CORRELATION_ID;
    PlatformAttributes.PLATFORM_CORRELATION_ID; TracingMdcKeys.REQUEST_ID/CORRELATION_ID
  - no public RequestContextAccessor/RequestIdentityAccessor/RequestIdentityContext/
    RequestIdentityBinder/RequestIdentityScope
  - single internal immutable per-execution identity store (platform-tracing-core plane)
  - ActiveTraceContextView is the only public read view; supplier reads the store, not MDC
  - RequestIdentityBoundarySupport in platform-tracing-spring-boot-autoconfigure; web adapters
    call the boundary only; CP-C2 ABI byte-for-byte unchanged
  - ReactiveCorrelationOperations (Mono/Flux) in platform-tracing-autoconfigure-webflux,
    package space.br1440.platform.tracing.webflux; Reactor types outside platform-tracing-api;
    signatures proven by spike and locked by CP-1
  - birth-time projection; canonical span attribute platform.correlation_id; generic baggage
    attribute for platform.correlation.id suppressed
  - canonical value [A-Za-z0-9_-], 1..128 ASCII, no trim/normalization; platform does not generate
    correlationId; not RequestIdSupport.sanitizeOrNull for internal binding
  - if TraceOperations present, assignment functional; sdk.mode=DISABLED keeps identity functional;
    invalid composition fails startup

Trust choice: F0 fail-closed; trusted inbound deferred to RG-IDENTITY-TRUST.
```

## 13. Current gate status

```text
CP-1 APPROVED
CP-1 REVISION: R2
SLICE H CLOSED
CP-1(a,b,c,d,f) APPROVED
SLICE M UNBLOCKED
SLICE G BLOCKED BY CP-2
RG-IDENTITY-TRUST OPEN
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```

Slice M ещё не начат и не завершён; данный пакет только разблокирует его старт.
