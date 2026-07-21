# CP-1 Identity Contract Decision Packet

> Дата: 2026-07-21
> Branch: `feature/cp1-identity-contract-decision`
> Base: `master@5215f43` (Slice H merged by PR #15)
> Статус: **PROPOSED / ARCHITECTURE REVIEW REQUIRED**
> Gate: **CP-1(a-d,f) OPEN; SLICE M BLOCKED**
> Ограничение: production-код, ABI snapshot и конфигурация не изменялись.

## 1. Executive decision request

Комитету предлагается принять CP-1 как единый identity-контракт. Пакет не переоткрывает
решённую модель `traceId != requestId != correlationId`, не переносит domain state в API и не
меняет утверждённый CP-C2 propagation port.

| Checkpoint | Предлагаемое решение | Статус |
|---|---|---|
| CP-1(a) | canonical baggage key `platform.correlation.id` | APPROVAL REQUIRED |
| CP-1(b) | `X-Correlation-ID` только как opt-in boundary bridge; baggage остаётся canonical transport; response header по умолчанию отсутствует | APPROVAL REQUIRED |
| CP-1(c) | вариант A: ingress-only projection на уже существующий span; late assignment влияет только на logical context, baggage, MDC и новые child spans | APPROVAL REQUIRED |
| CP-1(d) | exact OTel-free API из §3; synchronous scope; framework adapters владеют reactive/message lifetime | APPROVAL REQUIRED |
| CP-1(f) | fail-closed F0: все ingress untrusted до отдельного доказанного transport verifier; hostname/header heuristics запрещены | APPROVAL REQUIRED |

**Рекомендация:** принять предложенный контракт вместе с F0. Это разрешает безопасную реализацию
Slice M без выдуманного trust signal. Поддержка trusted gateway/s2s/message ingress добавляется
только после отдельного transport decision с authenticated evidence. Если F0 неприемлем, CP-1
остаётся OPEN до предоставления владельцем платформы точных authenticated signals.

## 2. Repository baseline

- `TraceOperations` сейчас содержит только `traceContext()` и `spans()`.
- `ActiveTraceContextView.correlationId()` всегда возвращает empty в
  `DefaultActiveTraceContextView`; `requestId()` отсутствует.
- `RequestIdSupport` уже фиксирует `[A-Za-z0-9_-]`, max 128 и reject/regenerate для requestId,
  но ошибочно называет requestId correlation id в документации.
- `TracingMdcKeys.CORRELATION_ID = "correlation_id"` фактически хранит requestId.
- `RequestTraceContextSnapshot` содержит только старое поле `correlationId`, traceId и spanId.
- `OutboundPropagationDecision`/`OutboundPropagationHeaders` CP-C2 описывают только control
  headers и requestId. Их форма не принадлежит Slice M и остаётся неизменной.
- Servlet/WebFlux имеют разные lifecycle boundaries; WebFlux уже использует Reactor/Micrometer
  context propagation. API-модуль не зависит от Spring, Reactor, Servlet, Kafka или OTel.
- Repository не содержит доказанного canonical authenticated signal для gateway, service mesh,
  RPC или Kafka ingress. Поэтому утверждать какой-либо такой ingress trusted нельзя.

## 3. CP-1(d): exact proposed public API

Все новые типы находятся в `space.br1440.platform.tracing.api.context` и используют только JDK и
`jakarta.annotation`. Ни один тип не содержит OTel/Spring/Reactor/Servlet/Kafka/gRPC/MDC.

### 3.1 RequestIdentityContext

```java
public interface RequestIdentityContext {

    @Nonnull
    Optional<String> requestId();

    @Nonnull
    Optional<String> correlationId();
}
```

Контракт read-only. Реализация immutable и internal. Методы никогда не возвращают `null`.
Пустой execution представлен объектом с двумя `Optional.empty()`, а не `null` context.

### 3.2 RequestContextAccessor

```java
public interface RequestContextAccessor {

    @Nonnull
    RequestIdentityContext current();
}
```

Accessor является stateless access port. Он не хранит current value в поле singleton и не имеет
write API. В Spring composition root существует ровно один effective bean:

- default bean регистрируется через `@ConditionalOnMissingBean(RequestContextAccessor.class)`;
- один user bean полностью заменяет default;
- два user bean приводят к диагностируемому startup failure;
- silent `@Primary`, arbitrary selection и static holder запрещены.

`current()` не должен бросать и возвращать `null`. Platform read paths защищаются от некорректного
user implementation: exception/null трактуются как empty context с bounded diagnostic. Значения
custom accessor обязаны быть canonical; перед span/transport projection платформа валидирует их
повторно.

### 3.3 CorrelationScope

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

### 3.4 TraceOperations delta

```java
@Nonnull
CorrelationScope openCorrelationScope(@Nonnull String correlationId);

void withCorrelationId(@Nonnull String correlationId, @Nonnull Runnable action);

<T> T withCorrelationId(@Nonnull String correlationId,
                        @Nonnull Callable<T> action) throws Exception;
```

Семантика:

- `null` argument/action -> `NullPointerException`;
- invalid correlationId -> `IllegalArgumentException` до изменения context;
- action исполняется ровно один раз; scope закрывается в `finally`;
- runtime/error/checked exception action не оборачивается и не подавляется;
- nested scopes восстанавливают предыдущие logical context, baggage и MDC в LIFO;
- возвращённый deferred object не получает lifetime propagation: метод scoped только на сам вызов.

### 3.5 ActiveTraceContextView delta

```java
@Nonnull
Optional<String> requestId();

@Nonnull
Optional<String> correlationId();
```

`requestId()` читает `RequestContextAccessor`, не MDC/static/header. `correlationId()` перестаёт
быть alias requestId и читает validated logical identity текущего execution.

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

## 4. Context ownership and lifecycle

| Mode/boundary | Storage/carrier | Writer | Reader/cleanup |
|---|---|---|---|
| Servlet | request attribute + supported internal OTel context bridge | Servlet ingress adapter | accessor; filter `finally` clears projections |
| WebFlux | immutable value in Reactor Context at subscription | WebFlux adapter | accessor through approved context bridge; `doFinally` handles complete/error/cancel |
| Kafka/message | immutable message-processing context, new requestId per message when absent | consumer adapter | listener execution scope closes after success/error |
| Scheduler | new execution-scoped requestId; correlationId only from trusted job metadata or app scope | scheduler adapter/app | execution scope close |
| Direct/imperative | internal scoped carrier backed by supported OTel Context mechanism | `TraceOperations` internal implementation | `CorrelationScope.close()` LIFO restore |
| Agent-only | no facade/accessor | none | not applicable |

`RequestIdentityContext` is immutable. A static `ContextKey` identifier inside implementation is
allowed only as a key; current request state is never stored in static/global mutable memory.
ThreadLocal-only WebFlux implementation and transfer between independent requests/messages are
forbidden.

Reactive ownership belongs to `platform-tracing-autoconfigure-webflux`. It must bind on
subscription, isolate concurrent subscribers and clear on completion/error/cancellation. No reactive
overload is added to `platform-tracing-api`.

## 5. Disabled and no-op semantics

- Identity is an infrastructure concern and remains available when telemetry emission is disabled.
- With `sdk.mode=DISABLED`, span creation remains NoOp, while valid correlation scopes and request
  identity access remain functional and reversible.
- With `platform.tracing.enabled=false`, default `RequestContextAccessor` and error snapshot supplier
  remain registered, but existing web tracing filters and `TraceOperations` remain absent. Default
  current identity is empty unless an independent approved infrastructure adapter/user bean provides
  it; Slice M does not silently add requestId generation or response headers to the fully disabled mode.
- A no-op `CorrelationScope` is permitted only when no identity implementation exists in direct
  manual composition; it still validates input and closes idempotently.
- Disabled mode must not leak state, emit baggage externally, or change action execution semantics.

## 6. CP-1(a,b): transport contract

### 6.1 Canonical keys

- Baggage: `platform.correlation.id`.
- Optional HTTP boundary bridge: `X-Correlation-ID`.
- Request identity remains `X-Request-Id`; it is never placed in baggage.
- Trace identity remains W3C `traceparent` / response `X-Trace-Id`.

### 6.2 Ingress conflict matrix

| Trust/input | Result |
|---|---|
| untrusted or unverifiable ingress | strip/ignore correlation baggage and header |
| trusted, one valid source | accept source |
| trusted, baggage and header valid and equal | accept once |
| trusted, valid but unequal sources | drop correlationId and emit bounded diagnostic |
| any duplicate canonical key/header occurrence | drop correlationId; do not fall back |
| any invalid/oversized/invalidly encoded occurrence | drop correlationId; do not generate business id |

Baggage wins only when the header is absent. Conflict never resolves through accidental parser order.
Other valid baggage members remain unchanged.

### 6.3 Egress and response

- Trusted internal egress carries correlationId through W3C baggage.
- `X-Correlation-ID` egress is an opt-in compatibility bridge and is disabled by default.
- External/untrusted egress strips `platform.correlation.id` and never writes the header.
- Response `X-Correlation-ID` is disabled by default; `X-Request-Id` and `X-Trace-Id` retain their
  existing contracts.
- The CP-C2 types `PlatformOutboundPropagation`, `OutboundPropagationDecision` and
  `OutboundPropagationHeaders` are unchanged. Correlation baggage/header handling belongs to the
  identity sanitizer/adapters, not the control-header port.

## 7. CP-1(c): validation and span projection

### 7.1 Canonical value

- length: 1..128 ASCII characters;
- allowlist: `[A-Za-z0-9_-]`;
- case preserved; no lowercase/uppercase normalization;
- no truncation or replacement;
- transport syntax is decoded exactly once by its standard parser;
- surrounding HTTP OWS may be removed by HTTP parsing, but canonical programmatic input is not
  silently trimmed;
- invalid ingress is dropped; invalid programmatic input throws `IllegalArgumentException`.

### 7.2 Proposed projection state machine: option A

1. Trusted ingress is sanitized before auto-instrumented server span creation.
2. Sanitized ingress correlationId may be projected once to `platform.correlation_id`.
3. Late `openCorrelationScope(B)` changes logical context, baggage and MDC only.
4. It never rewrites/removes the existing parent span attribute.
5. Child spans created inside scope B receive B during their own creation/projection.
6. Two concurrent late assignments to one active parent span do not mutate that parent span and
   therefore have deterministic behavior without CAS or lock ownership over OTel `Span`.

Span attributes remain settable/last-write-wins but non-removable; platform policy deliberately
avoids relying on last-write-wins. Option B (serialized late writer) is rejected as unnecessary
global coordination. Option C (reject all late scope creation) is rejected because it removes useful
child-operation correlation without reducing ingress risk beyond option A.

## 8. CP-1(f): trust contract

### 8.1 Proposed F0 baseline

No repository evidence identifies an authenticated gateway/mesh/RPC/Kafka trust signal. Therefore:

- every external or unproven ingress is untrusted;
- header names, source hostname/IP, `Forwarded`/`X-Forwarded-*`, topic name and arbitrary request
  attributes are not authentication;
- spoofable `X-Trusted-*` style headers are forbidden;
- default sanitizer strips business correlation identity before server/consumer span creation;
- programmatic application scopes remain supported because they are not transport trust claims.

### 8.2 What is required to enable trusted ingress later

For each transport, a supplemental accepted decision must name:

1. canonical authenticated signal (for example, verified mTLS workload identity, not its raw header);
2. verifier implementation and owning module/team;
3. configuration source and rotation/failure behavior;
4. exact pre-span/pre-consumer execution point;
5. negative tests for spoof, missing verifier, verifier exception and ambiguous credentials.

Until all five are supplied, that transport remains F0/untrusted. A universal `Object` carrier,
generic `Map`, shared hostname heuristic or adapter-local policy is prohibited.

## 9. Public ABI and configuration impact

Intentional API changes owned by CP-1/M:

- ADD `CorrelationScope`, `RequestIdentityContext`, `RequestContextAccessor`;
- ADD three methods to `TraceOperations`;
- ADD `ActiveTraceContextView.requestId()`;
- REDEFINE `ActiveTraceContextView.correlationId()`;
- REPLACE `RequestTraceContextSnapshot` shape with four independent fields;
- ADD `PlatformHeaders.X_CORRELATION_ID`;
- ADD `PlatformAttributes.PLATFORM_CORRELATION_ID = "platform.correlation_id"`;
- ADD `TracingMdcKeys.REQUEST_ID = "requestId"`;
- REDEFINE `TracingMdcKeys.CORRELATION_ID = "correlationId"`.

No deprecated aliases, dual-write, compatibility constructors or legacy read fallback. ABI snapshot
must exactly reflect this list. CP-C2 symbols must remain byte-for-byte unchanged.

Proposed Spring configuration is intentionally minimal:

- default trust mode is fixed fail-closed, not a permissive boolean;
- boundary `X-Correlation-ID` ingress/egress/response bridges default disabled;
- enabling trusted ingress requires an approved transport verifier, not only a property;
- competing `RequestContextAccessor` beans fail startup.

Exact property names are implementation-owned internal configuration and must be listed in the M3
review before binding; they are not silently inferred from existing requestId properties.

## 10. Operational migration

- Log schema changes from legacy `correlation_id` carrying requestId to independent
  `requestId` and `correlationId` camelCase keys.
- Error snapshot and downstream error-handling mapping gain requestId; old correlation alias is
  removed in one coordinated pre-production change.
- Dashboards, log queries, alerts and runbooks must stop treating requestId as business
  correlationId.
- Existing `X-Request-Id` response/forward behavior is preserved.
- Rollback is full revert of Slice M. Partial rollback or dual-write is forbidden because it would
  reintroduce ambiguous identity semantics.

## 11. Mandatory implementation evidence after approval

| Area | Required proof |
|---|---|
| API/ABI | exact allowlist and snapshot; no framework/OTel types; CP-C2 entries unchanged |
| Synchronous | nested/idempotent scopes, exception restore, invalid/null behavior, same-thread contract |
| Reactive | `Mono.defer`, repeat/resubscribe, two concurrent subscribers, publishOn/subscribeOn, nested, complete/error/cancel cleanup |
| Servlet | request attribute lifetime, exception/async cleanup, no MDC source-of-truth |
| Message/scheduler | new requestId per execution, redelivery/retry semantics, no cross-message leakage |
| Projection | ingress attr once; late parent unchanged; child span receives scoped correlationId; concurrent same-parent deterministic |
| Trust | raw/spoofed/duplicate/invalid ingress stripped before span; verifier absence/failure fail-closed |
| Egress | trusted baggage only; external destination stripped; optional header default off |
| Disabled | identity isolation remains functional; spans remain NoOp; no outbound leakage |
| Agent | packaged Spring+Agent context visibility; exactly one sanitizer and projection owner; no second SDK |
| Spring topology | webmvc-only, webflux-only, both starters, one override, two overrides fail-fast |

## 12. Committee resolution

Slice M may start only when one resolution is recorded:

```text
CP-1 APPROVED AS PROPOSED
Revision: <commit SHA>
Trust choice: F0 fail-closed
CP-1(c): option A ingress-only projection
```

or:

```text
CP-1 CHANGES REQUIRED
Rejected sections: <a|b|c|d|f>
Replacement exact contract: <text/ADR revision>
```

Silence, partial approval, `TBD`, an unverified trust header or approval of API signatures without
trust/concurrency semantics does not close CP-1.

## 13. Current gate status

`SLICE H CLOSED`; `CP-1(a-d,f) OPEN`; `SLICE M BLOCKED`; `SLICE G BLOCKED BY CP-2`;
`RG-CONTROLLED-AGENT OPEN`; `PRODUCTION ROLLOUT FORBIDDEN`.
