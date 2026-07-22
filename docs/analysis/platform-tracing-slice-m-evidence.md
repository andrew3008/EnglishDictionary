# Slice M: Identity Model Completion Evidence

> Дата: 2026-07-21
> Ветка: `feature/slice-m-identity-model`
> Base: `master` / `497cf748883d21f7e96c99d19271bc98f718d252`
> Проверенный HEAD до evidence-коммита: `fc45c175e79e24efdeeb46863abafed913153c50`
> Архитектурный контракт: `CP-1 APPROVED (R2)`
> Вердикт реализации: **SLICE M PASS**

## 1. Scope и ownership

Slice M реализован как единый identity contract с логическими review-коммитами M1-M5:

| Часть | Реализованный scope | Основные модули |
|---|---|---|
| M1 | Минимальный approved public API, ABI snapshot, раздельные requestId/correlationId constants | `platform-tracing-api`, `platform-tracing-core` |
| M2 | Единое immutable per-execution состояние, синхронные scopes, LIFO restore, disabled/no-op semantics | `platform-tracing-core` |
| M3 | F0 fail-closed ingress sanitizer и birth-time correlation projection без изменения parent span | `platform-tracing-otel-extension` |
| M4 | WebMVC, WebFlux, Kafka, MDC/span/error projections и lifecycle cleanup | Spring autoconfigure, WebMVC, WebFlux, E2E |
| M5 | ABI/architecture gates, controlled-Agent fixtures, aggregate и packaged evidence | tests, E2E, docs |

Коммиты реализации до этого документа:

- `d40129a` — approved scoped identity context;
- `19b1adb` — fail-closed correlation ingress;
- `ab49c02` — request/correlation projections;
- `fdb96f7` — starter dependency smoke baseline;
- `f2525e7` — message identity integration;
- `a73d327` — удаление stale identity aliases;
- `2efaea4` — порядок Kafka outbound propagation wiring;
- `39fefb9` — canonical correlation projection;
- `9efbcb7` — WebFlux LIFO restore;
- `52ebc1b` — controlled-Agent identity paths;
- `32b7a37` — alignment extension baggage defaults;
- `fc45c17` — controlled ownership в Agent fixtures.

## 2. Изменения по concern

### Public API и ABI

- `CorrelationScope` и approved методы `TraceOperations.openCorrelationScope/withCorrelationId`;
- `ActiveTraceContextView.requestId()`;
- отдельные `TracingMdcKeys.REQUEST_ID=requestId` и `CORRELATION_ID=correlationId`;
- `PlatformAttributes.PLATFORM_REQUEST_ID` и `PLATFORM_CORRELATION_ID`;
- `RequestTraceContextSnapshot(requestId, correlationId, traceId, spanId)`;
- `PlatformHeaders.X_CORRELATION_ID` как opt-in bridge, не активированный по умолчанию;
- WebFlux API `ReactiveCorrelationOperations` находится в WebFlux-модуле, Reactor-типы не попали в `platform-tracing-api`.

ABI delta зафиксирован в
`platform-tracing-core/src/test/resources/abi/platform-tracing-api-core.txt` и проверен
architecture gate. Публичные `RequestContextAccessor`, `RequestIdentityContext`,
`RequestIdentityBinder`, `RequestIdentityScope` не созданы. CP-C2 propagation-port signatures
не изменены; добавленный header constant принадлежит approved CP-1(b), а не redesign CP-C2.

### Internal ownership

- `ThreadLocalIdentityStorage` владеет synchronous direct/no-op execution state;
- `OtelIdentityStorage` связывает identity с поддерживаемым OTel Context/Baggage в application plane;
- `RequestIdentityBoundarySupport` является internal stateless boundary adapter;
- mutable singleton, global current value и Kafka-specific source of truth отсутствуют;
- requestId не читается из traceId/spanId/correlationId/baggage.

## 3. Семантика lifecycle

### Synchronous

Scope сохраняет предыдущее immutable состояние и восстанавливает его строго LIFO. Закрытие
идемпотентно; cross-thread close отвергается. Позднее назначение correlationId не изменяет
родительский span: оно влияет на logical context, baggage/MDC и новые child spans.

### WebMVC

Servlet filter сохраняет валидный inbound requestId либо генерирует UUIDv4 при отсутствии или
отклонении значения. Request identity binding закрывается в `finally`. Непроверенный business
correlation ingress не принимается; локальный programmatic scope доступен приложению.

### WebFlux

Identity хранится per-subscription в Reactor Context и переносится через зарегистрированный
Micrometer accessor. `ReactiveCorrelationOperations` поддерживает позднее назначение для
`Mono`/`Flux`; restore выполняется LIFO. Доказаны `publishOn`, `subscribeOn`, concurrent requests,
error/cancel cleanup и отсутствие leakage.

### Kafka/message

- producer передаёт существующий валидный `X-Request-Id`, но не синтезирует его при отсутствии;
- consumer сохраняет валидный requestId или генерирует UUIDv4 при absent/invalid;
- повторная доставка того же record сохраняет requestId в canonical header;
- batch records и concurrent executions получают независимые scopes;
- cleanup выполняется после success/error;
- native `correlation_id`, topic/group metadata и непроверенный `platform.correlation.id` не
  считаются trusted business correlation;
- requestId не помещается в baggage;
- существующие Kafka span/link semantics сохранены;
- Spring application не создаёт второй OpenTelemetry SDK bean.

## 4. F0, ingress и egress

`FailClosedCorrelationBaggagePropagator` удаляет непроверенный inbound
`platform.correlation.id` до projection. Spoofable header/host/topic heuristics отсутствуют.
Trusted ingress не реализован и остаётся в `RG-IDENTITY-TRUST`.

Локально назначенный correlationId помещается в canonical baggage для разрешённого outbound
пути. `BaggageSpanProcessor` проецирует этот ключ только в `platform.correlation_id` и подавляет
generic `baggage.platform.correlation.id`. В application-owned manual span тот же canonical
атрибут устанавливается при создании span через `OtelTracingRuntime`; это разные composition
planes, а не два processor owner в одном SDK pipeline.

## 5. Projections и disabled behavior

| Surface | requestId | correlationId |
|---|---|---|
| MDC | `requestId` | `correlationId` |
| Span | `platform.request_id` | `platform.correlation_id` |
| Error snapshot | logical identity view | logical identity view |
| Baggage | запрещён | `platform.correlation.id` для approved local/egress path |

Error snapshot больше не использует MDC как source of truth. Legacy MDC key `correlation_id`
остаётся только в negative characterization test, доказывающем отсутствие dual-read.

При выключенной span emission identity scopes остаются функциональными в runtime-backed facade.
No-op runtime не создаёт span и не ломает application action; request/correlation context
восстанавливается после scope. `sdk.mode=DISABLED` при живом Agent не останавливает agent-owned
instrumentation, что соответствует принятой composition model.

## 6. Classloader и Agent ownership

Application plane владеет facade, logical identity API, WebMVC/WebFlux/Kafka adapters и reader.
Agent extension plane владеет F0 sanitizer и единственным SDK processor для business-correlation
projection. Platform objects между application CL и agent extension CL не передаются; связь идёт
через поддерживаемые OTel Context/Baggage и существующие wire boundaries.

Controlled-Agent E2E использует реальный forked JVM, подготовленный platform Agent distribution и
реальную extension. Fixture исключает Boot application-owned SDK autoconfiguration при подключённой
extension. Readiness определяется точной строкой `READY`, а не подстрокой `AGENT_READY`.

## 7. Verification evidence

### Focused и aggregate

```powershell
.\gradlew.bat :platform-tracing-api:test `
  :platform-tracing-core:test `
  :platform-tracing-spring-boot-autoconfigure:test `
  :platform-tracing-autoconfigure-webmvc:test `
  :platform-tracing-autoconfigure-webflux:test `
  :platform-tracing-otel-extension:test `
  pr0StarterDependencySmoke pr1ModuleTaxonomyVerify `
  pr4ArchitectureFitnessVerify build --no-daemon
```

Результат: `BUILD SUCCESSFUL`, 128 tasks, architecture/module/starter gates GREEN.
Обычная `build` намеренно показывает opt-in E2E task как `SKIPPED`; это не использовалось как
evidence обязательного E2E, который выполнен отдельно ниже.

```powershell
.\gradlew.bat :platform-tracing-bench:compileJmhJava --no-daemon
```

Результат: `BUILD SUCCESSFUL`.

### Mandatory packaged controlled-Agent E2E

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks --no-daemon
```

Collector/backend fixtures использовали IP endpoints; Windows bind mounts к remote Docker не
использовались. Результат из свежих XML:

```text
suites=28 tests=65 failures=0 errors=0 skipped=0
```

Отдельный mandatory identity/ownership subset до полного прогона: 17 tests, 0 failures,
0 errors, 0 skipped. Полный прогон покрывает WebMVC, WebFlux, Kafka, F0 security, Agent
attestation, Spring composition и classloader visibility.

### Repository scans

- forbidden public identity types: 0;
- active/orphan `IDENT-1..IDENT-5` tags: 0;
- legacy `correlation_id` request read/write в main: 0;
- requestId baggage path: 0;
- generic `baggage.platform.correlation.id` production attribute: 0;
- новых wildcard imports: 0 (старые вне Slice M остаются baseline debt);
- Java UTF-8 BOM: 0;
- `git diff --check`: ошибок whitespace нет;
- mandatory E2E skipped: 0;
- API OTel/framework purity, exact ABI snapshot и WebFlux surface: GREEN через
  `pr4ArchitectureFitnessVerify` и module tests.

## 8. IDENT closure

| ID | Закрывающее evidence | Статус |
|---|---|---|
| IDENT-1 | legacy `correlation_id` больше не является requestId MDC key/read path | **CLOSED** |
| IDENT-2 | requestId и correlationId имеют независимые immutable lifecycle/scopes | **CLOSED** |
| IDENT-3 | `ActiveTraceContextView` возвращает реальные requestId/correlationId | **CLOSED** |
| IDENT-4 | birth-time canonical projection, parent не мутируется при late assignment | **CLOSED** |
| IDENT-5 | Web/Kafka/error projections мигрированы без aliases и dual-write | **CLOSED** |

Реестр `KnownDefectId` пуст; старые IDENT assertions переведены в положительные/negative security
контракты и выполняются обычными test tasks.

## 9. Findings и external coordination

### P0/P1

Открытых P0/P1 по Slice M нет. В ходе полного E2E были обнаружены и исправлены stale test-fixture
assumptions: application SDK coexistence, отсутствие controlled extension, неточная readiness
подстрока и старые JMX ObjectNames. Production architecture для их исправления не менялась.

### P2

`validateCollectorConfigs` при remote Docker сообщает invalid Windows volume specification, но task
имеет существующую non-blocking fallback semantics и aggregate build остаётся GREEN. Это отдельное
test-infrastructure ограничение remote Docker, не Slice M runtime defect.

### ErrorHandlingMdcKeys

Тип `ErrorHandlingMdcKeys` отсутствует в main/test dependency graph и встречается только в
историческом CP-1 review. Точный внешний artifact/type/version: **INSUFFICIENT_EVIDENCE**.
Локальный shadow type и compatibility dual-write не добавлялись. Если внешний error-handling
starter существует, его coordinated release обязан перейти на `requestId`/`correlationId` до
production rollout.

## 10. Gate resolution

```text
SLICE M COMPLETED
IDENT-1 CLOSED
IDENT-2 CLOSED
IDENT-3 CLOSED
IDENT-4 CLOSED
IDENT-5 CLOSED
SLICE I UNBLOCKED
RG-IDENTITY-TRUST OPEN
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```

Следующий разрешённый этап: **Slice I**. Этот документ не начинает и не реализует Slice I.
Trusted inbound business correlation и controlled-Agent rollout остаются отдельными release gates.
