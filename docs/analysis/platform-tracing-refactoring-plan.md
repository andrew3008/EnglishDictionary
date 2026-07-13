# PlatformTracing — план рефакторинга v3.4.2

> Canonical architecture plan (v3.4.2 architect-review correction patch). Approved public API: `platformtracing_full_production_public_interfaces_ru_v3.md`.

## 1. Executive Summary

Дефект R01 (Critical): `MeteredPlatformTracing` — `@Primary` бин при наличии Micrometer — молча теряет `ROOT`/`DETACHED`/links через interface default-dispatch. Патч не поможет — архитектура делает такие дефекты структурно неизбежными. Решение: узкий фасад без поведенческих defaults (`traceContext()` + `manual()`), декорирование на полностью абстрактной внутренней границе `TracingImplementation`.

Проект не вышел в production → ломающие изменения приемлемы и необходимы. Обратная совместимость не является ограничением; deprecate-first migration не применяется.

**Current canonical decisions:**

- `PlatformTracing` is a narrow facade: `traceContext()` + `manual()`.
- No behavioral default methods on public facade, builders, or internal SPI.
- Auto-instrumentation is the default; `manual()` is used only when automatic instrumentation is not enough.
- Manual tracing entry points are `operation(name)`, `transport()`, and governed `spanFromSpec(spec)`.
- `SpanSpec.builder(...)` uses readable public grammar: `.child()`, `.root()`, `.detached()`, `.linkedTo(...)`.
- `SpanOptions` is a public immutable value model returned by `SpanSpec.options()`, but not the preferred application-facing builder grammar.
- `SpanSpec.attributes()` remains `Map<String, SpanSpecAttributeValue>`; builder attribute overloads normalize into `SpanSpecAttributeValue`.
- `SpanSpecReason` is mandatory; generic reasons such as `OTHER`, `UNKNOWN`, `CUSTOM`, and `MISC` are forbidden.
- Links are pre-start only; post-start `addLink(...)` is removed.
- `ROOT + links` is allowed; `DETACHED + links` is forbidden; `CHILD + links` is forbidden in v1 (future extension only).
- v3.4 adds proof gates from multi-model architecture review: Micrometer Observation ADR, bean singularity, single creation boundary, diagnostics DTO stability, expanded removed-symbol grep, and named test suites for key risks.
- v3.4.1 clarifies verification ownership: `TracingImplementationRoutingTest` belongs to core, `BeanTopologyTest` belongs to Spring Boot autoconfigure, and Slice 7 owns the full autoconfiguration matrix.
- v3.4.2 closes architect-review document defects: unified Slice 0B test name, `TracingDiagnostics` vs `TracingDiagnosticsView` roles, risk-register ID footnote, Slice 6 verification parity, `justification(String)` wording, `TEMPORARY_WORKAROUND` periodic audit.
- `SpanRelation` is removed completely.
- Metering/decorators operate on fully abstract internal `TracingImplementation`.
- Internal implementation state is represented by internal `TracingState`, not a plain boolean; Actuator/diagnostics expose a separate stable diagnostics DTO, not the internal state object directly.
- STRICT validation is the production default; WARN is local/dev only.
- Slice 0A/0B create evidence before breaking changes.

**Формула auto-instrumentation:**

```text
Auto-instrumentation is the default.
Use PlatformTracing.manual() only when automatic instrumentation is not enough.
```

---

## Architecture Decision Required Before Slice 1A: Micrometer Observation API Boundary

Before Slice 1A, add an ADR: `ADR-platform-tracing-micrometer-observation-boundary.md`.

The ADR must decide how `TracingImplementation` relates to Spring Boot 3 / Micrometer Observation API.

Accepted options to evaluate:

A. PlatformTracing controls manual tracing directly through OpenTelemetry SDK.
   - Spring auto-observation remains separate.
   - The plan must define coexistence and duplicate-span prevention rules.

B. PlatformTracing is implemented as a bridge over Micrometer Observation API.
   - The plan must define how OTel links, SpanSpec governance, and typed semantic builders map to Observation.

C. Hybrid model.
   - Auto-observation remains Spring/Micrometer-owned.
   - Platform manual tracing remains governed through `TracingImplementation`.
   - The plan must define one source of truth per telemetry path and tests proving no duplicate root spans.

The ADR must explicitly answer:
- Which component owns trace lifecycle for manual platform spans?
- Which component owns trace lifecycle for Spring auto-instrumented spans?
- How duplicate spans are prevented?
- How Micrometer tracing bridge coexists with `MeteredTracingImplementation`?
- Whether any Spring tracing handlers must be disabled, customized, or left untouched?
- Which tests prove coexistence?

The ADR `ADR-platform-tracing-micrometer-observation-boundary.md` is **Accepted** and selects Option C — Hybrid model.
Slice 1A is no longer blocked by the Micrometer Observation API Boundary ADR.
Slice 1A still requires the remaining architect sign-offs listed in §9 where applicable; Slice 1A gate decisions were approved 2026-07-06.
Slice 1A scope remains additive only (API skeleton and architecture tests).

---

## 2. Подтверждённые факты репозитория

| Факт | Файл:строка | Следствие |
|------|-------------|-----------|
| `PlatformTracing` — 4 abstract, ~30 behavioral default методов | `PlatformTracing.java:29-60` | Любой decorator без переопределений молча ломает семантику |
| `startSpan(name, category, relation)` default игнорирует relation | `PlatformTracing.java:72-77` | Корень R01; должен быть устранён, не пропатчен |
| `startSpanWithLinks` default дропает links; `addLink` — no-op | `PlatformTracing.java:110-122` | Links должны перейти на pre-start модель |
| `inSpan` — 4 default-метода с Javadoc-запретом переопределения | `PlatformTracing.java:256-358` | Tribal knowledge вместо структурного запрета |
| `MeteredPlatformTracing` переопределяет только `startSpan(2-arg)`, builder factories, `currentTraceId/Id`, `recordException` | `MeteredPlatformTracing.java` (до строки 118) | R01 подтверждён: relation/links/inSpan деградируют через default |
| `MeteredPlatformTracing` зарегистрирован `@Primary` при наличии Micrometer | `TracingMetricsAutoConfiguration.java:106-112` | Каждое Micrometer-enabled приложение получает дефектный bean |
| Нет теста `MeteredPlatformTracing` + `SpanRelation.ROOT` или links | `DefaultPlatformTracingTest.java`, `PlatformTracingMetricsTest.java` | R01 не воспроизведён ни одним тестом |
| `DefaultPlatformTracing` корректно реализует relation/links | `DefaultPlatformTracing.java:159-231` | Корректное поведение есть; нужно сохранить через граничное тестирование |
| **9 классов для удаления в enabled-режиме**: 8 `Facade*SpanBuilder` + `AbstractFacadeTypedSpanBuilder` | `span/builder/` package | Все обходят semconv-валидацию и anti-double guard |
| `AbstractPlatformSpanBuilder` anti-double guard привязан к raw OTel `Context` | `AbstractPlatformSpanBuilder.java:113-119` | Миграция guard'а на `TracingImplementation` — safety-critical |
| Kill-switch `facadeEnabled`/`setFacadeEnabled` в `DefaultPlatformTracing` | `DefaultPlatformTracing.java:54, 194, 202, 213` | `TracingState` replaces/preserves kill-switch semantics via `TracingImplementation.state()` в Срезе 2 |

---

## 3. Целевая публичная архитектура

### 3.1 PlatformTracing — узкий фасад

Только два аксессора, ноль behavioral methods:

```java
public interface PlatformTracing {

    TraceContextView traceContext();

    ManualTracing manual();
}
```

- `traceContext()` — read-only view активного trace/span context для correlation, logging, error models.
- `manual()` — entry point для platform-governed manual tracing.
- Auto-instrumentation — режим работы платформы/агента/стартеров, **не** метод фасада (`auto()` отсутствует).

### 3.2 TraceContextView

```java
public interface TraceContextView {

    Optional<String> traceId();

    Optional<String> spanId();

    Optional<String> correlationId();
}
```

Read-only view. Не раскрывает OTel `Context`, `Span`, `SpanContext`. Без `sampled()` (defer до появления конкретного consumer).

### 3.3 ManualTracing

```java
public interface ManualTracing {

    OperationSpanBuilder operation(@Nonnull String name);

    TransportTracing transport();

    SpecifiedSpan spanFromSpec(@Nonnull SpanSpec spec);
}
```

| Метод | Назначение |
|-------|------------|
| `operation(name)` | Стандартный supported path для ручной трассировки операции приложения |
| `transport()` | Semantic builders для ручных transport/protocol span'ов (HTTP, Database, RPC, Kafka) |
| `spanFromSpec(spec)` | Governed full-spec path для нестандартных span request через `SpanSpec` |

### 3.4 TransportTracing

```java
public interface TransportTracing {

    HttpTracing http();

    DatabaseTracing database();

    RpcTracing rpc();

    KafkaTracing kafka();
}
```

Transport builders **не** размещаются на `PlatformTracing` и **не** размещаются напрямую на `ManualTracing`. Future transport builders, such as Redis or S3, require explicit API proposal and are out of v3.4 scope.

### 3.5 SpecifiedSpan

`spanFromSpec(spec)` возвращает `SpecifiedSpan` — immutable terminal surface, не mutable builder:

```java
public interface SpecifiedSpan {

    SpanHandle start();

    void run(@Nonnull Runnable action);

    <T> T call(@Nonnull Supplier<T> supplier);

    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;
}
```

`SpanSpec` — единственный источник истины для name/category/topology/links/attributes/reason/reference.

### 3.6 SpanSpec и governance

```java
public interface SpanSpec {

    static SpanSpecBuilder builder(@Nonnull String name);

    String name();

    SpanCategory category();

    SpanOptions options();

    Map<String, SpanSpecAttributeValue> attributes();

    SpanSpecReason reason();

    Optional<String> reference();
}
```

Public builder ergonomics must not weaken the immutable `SpanSpec` contract.
`SpanSpec` stores only normalized `SpanSpecAttributeValue` instances.

`SpanSpecAttributeValue` — public value type или sealed hierarchy, допускающий только OpenTelemetry-compatible scalar/list values:

- `String`
- `long` / `Long`
- `double` / `Double`
- `boolean` / `Boolean`
- `List<String>`
- `List<Long>`
- `List<Double>`
- `List<Boolean>`

Arbitrary `Object` values forbidden. Все attributes проходят `AttributePolicy` validation до span start. Invalid attribute types fail fast в STRICT mode.

**`SpanSpecAttributeValue` invariants:**

- immutable value object;
- null attribute values are forbidden;
- list values must be immutable defensive copies;
- list elements must be non-null;
- each list must contain elements of one supported type only;
- arbitrary objects, maps, nested collections, byte arrays, and domain objects are forbidden;
- all values are validated by `AttributePolicy` before span start;
- invalid values fail fast in STRICT mode.

List attribute overloads must defensively copy input lists.
Null lists are forbidden.
Empty lists are allowed only if the platform explicitly accepts them in `AttributePolicy`; otherwise they fail fast in STRICT mode.
Mixed-type lists are forbidden.

**Preferred implementation shape:**

- use a sealed interface `SpanSpecAttributeValue` with small immutable implementations/factories when project style allows it;
- if sealed interfaces are not aligned with repository style, use final immutable value classes/factories;
- regardless of implementation shape, the public contract must preserve the same whitelist, immutability, no-null, defensive-copy, and STRICT validation rules.

**`SpanSpec` / `SpanOptions` ownership:**

- `SpanSpec` does not expose links directly.
- Topology and links are stored in `SpanOptions`, which is returned by `SpanSpec.options()`.
- `SpanOptions` is a public immutable value model returned by `SpanSpec.options()`, but it is not the preferred application-facing builder grammar. Application code should use `.child()`, `.root()`, `.detached()`, and `.linkedTo(...)` on `SpanSpecBuilder`.
- `SpanSpecBuilder` is allowed to provide convenience methods `.child()`, `.root()`, `.detached()`, `.linkedTo(...)`, but these methods only build the resulting `SpanOptions`.
- Do not add `SpanSpec.links()` to the public API. This prevents duplicate sources of truth such as `SpanSpec.links()` and `SpanOptions.links()`.

**Public `SpanSpecBuilder` grammar (conceptual public builder shape, not implementation):**

```java
public interface SpanSpecBuilder {

    SpanSpecBuilder category(@Nonnull SpanCategory category);

    SpanSpecBuilder child();

    SpanSpecBuilder root();

    SpanSpecBuilder detached();

    SpanSpecBuilder linkedTo(@Nonnull RemoteSpanLink... links);

    SpanSpecBuilder attribute(@Nonnull String key, @Nonnull String value);

    SpanSpecBuilder attribute(@Nonnull String key, long value);

    SpanSpecBuilder attribute(@Nonnull String key, double value);

    SpanSpecBuilder attribute(@Nonnull String key, boolean value);

    SpanSpecBuilder stringListAttribute(@Nonnull String key, @Nonnull List<String> values);

    SpanSpecBuilder longListAttribute(@Nonnull String key, @Nonnull List<Long> values);

    SpanSpecBuilder doubleListAttribute(@Nonnull String key, @Nonnull List<Double> values);

    SpanSpecBuilder booleanListAttribute(@Nonnull String key, @Nonnull List<Boolean> values);

    SpanSpecBuilder reason(@Nonnull SpanSpecReason reason);

    SpanSpecBuilder reference(@Nonnull String reference);

    SpanSpec build();
}
```

`SpanSpecBuilder` intentionally exposes ergonomic typed attribute overloads for application code.
All accepted values are normalized into `SpanSpecAttributeValue` before `SpanSpec` is built.
`SpanSpec.attributes()` remains the strict immutable view: `Map<String, SpanSpecAttributeValue>`.
Do not add `attribute(String, Object)`.
Do not add raw map-based attribute injection to the public builder.

`SpanSpec.builder(name)` is an allowed static factory on an immutable/spec type.
It is not a behavioral static helper.
It must not start spans, mutate context, access OpenTelemetry `Context`, record exceptions, or perform lifecycle work.

Public `SpanSpec.builder(...)` intentionally exposes topology methods directly:

- `.child()`
- `.root()`
- `.detached()`
- `.linkedTo(...)`

This keeps call sites readable for application developers and architects. The builder produces `SpanOptions` as the topology/link value model. `SpanOptions` is a public immutable value model returned by `SpanSpec.options()`, but it is not the preferred application-facing builder grammar. Application code should use `.child()`, `.root()`, `.detached()`, and `.linkedTo(...)` on `SpanSpecBuilder` and should not need to write `.options(SpanOptions.root())` or `.topology(SpanOptions.root())`.

**`SpanSpecBuilder` final-state validation (order-independent):**

`SpanSpecBuilder` validates the final built state, not only the call order.

Examples:

- `.root().linkedTo(link)` is valid.
- `.linkedTo(link).root()` is also valid.
- `.detached().linkedTo(link)` is invalid.
- `.linkedTo(link).detached()` is also invalid.
- `.child().linkedTo(link)` is invalid in v1.
- `.linkedTo(link).child()` is invalid in v1.

The builder must not rely on call order to enforce topology/link policy. Validation happens before `build()` returns a `SpanSpec` and again before span start in STRICT mode.

**Repeated setter policy**

`SpanSpecBuilder` uses fail-fast repeated setter semantics.

Rules:
- first topology setter wins;
- calling another topology setter after `.child()`, `.root()`, or `.detached()` fails fast;
- repeated `reason(...)` fails fast;
- repeated `reference(...)` fails fast;
- duplicate attribute keys fail fast;
- repeated `linkedTo(...)` calls are additive, but final topology/link policy is validated before `build()` and again before span start in STRICT mode.

Examples:
- `.child().root()` is invalid;
- `.root().detached()` is invalid;
- `.reason(A).reason(B)` is invalid;
- `.attribute("x", "a").attribute("x", "b")` is invalid;
- `.root().linkedTo(a).linkedTo(b)` is valid;
- `.detached().linkedTo(a)` is invalid;
- `.linkedTo(a).detached()` is invalid.

Required tests: `SpanSpecBuilderFinalStateTest`.

```java
public enum SpanSpecReason {
    UNSUPPORTED_PROTOCOL,
    UNSUPPORTED_LIBRARY,
    LEGACY_INTEGRATION,
    PLATFORM_EDGE_CASE,
    TEMPORARY_WORKAROUND
}
```

- `reason()` — **обязателен**, controlled enum.
- `reference()` — опционален, рекомендуется для production workarounds, legacy integration, architecture-approved exceptions (ticket, ADR, issue).
- `justification(String)` **намеренно не используется** — see §3.11 Rejected names: unstructured free text without governance value.

**Governance для `SpanSpecReason`:**

- Adding a new reason requires platform architecture approval or ADR.
- Generic reasons are forbidden.
- Do not add `OTHER`, `UNKNOWN`, `CUSTOM`, `MISC`, or similar catch-all values.
- If a use case does not fit an existing reason, the team must decide whether to add a precise reason or introduce a semantic builder.

**Governance для `TEMPORARY_WORKAROUND`:**

If `reason == TEMPORARY_WORKAROUND`:

- `reference()` is required;
- the reference must point to a ticket/ADR/issue describing removal conditions;
- the usage must be visible in diagnostics;
- platform review is required before production use;
- docs must state that temporary workaround is not a permanent category;
- periodic audit of active `TEMPORARY_WORKAROUND` references is required (via diagnostics visibility and release-bound or scheduled platform review); stale or expired references must be escalated, not silently tolerated.

Builder example:

```java
SpanSpec spec = SpanSpec.builder("legacy-protocol-call")
    .category(SpanCategory.INTERNAL)
    .root()
    .linkedTo(linkContext)
    .attribute("legacy.protocol", "x-proprietary")
    .reason(SpanSpecReason.UNSUPPORTED_PROTOCOL)
    .reference("PLATFORM-1234")
    .build();
```

Governance rules:
1. `SpanSpec.reason()` обязателен.
2. STRICT mode валидирует spec до start.
3. Diagnostics metrics: `platform.tracing.manual.spec_spans.started`, `.rejected`, `.by_reason`.
4. **Inside the platform repository:**
   - ArchUnit governance for `manual().spanFromSpec(spec)` usage is **mandatory**.
   - Platform code using `spanFromSpec` must be in allow-listed packages or annotated with an approved suppression/governance annotation.
   - Every usage must include `SpanSpec.reason()`.
   - `TEMPORARY_WORKAROUND` additionally requires `reference()`.
   - Diagnostics must count spec-based span usage by reason and with/without reference.
   - **For consuming services:** the platform should provide recommended ArchUnit/build-plugin enforcement; runtime STRICT validation is mandatory regardless of downstream build enforcement.

**Runtime governance for `spanFromSpec`**

`spanFromSpec` governance must not rely only on repository-local ArchUnit rules.

STRICT mode must enforce at runtime:
- `SpanSpec.reason()` is mandatory;
- `TEMPORARY_WORKAROUND` requires `reference()`;
- `reference()` must match a configured ticket/ADR pattern when reason is `TEMPORARY_WORKAROUND`;
- generic reasons are forbidden by enum design;
- invalid `SpanSpec` fails before span start;
- diagnostics count `spanFromSpec` usage by reason and with/without valid reference.

For consuming services:
- platform-provided ArchUnit/build-plugin enforcement is recommended;
- runtime STRICT validation is mandatory and must work even when downstream build enforcement is not installed.

Required tests: `SpanSpecGovernanceTest`.

5. Documentation order: сначала `operation`, потом `transport`, затем `spanFromSpec`.

### 3.7 SpanOptions и Topology

```java
public interface SpanOptions {

    Topology topology();

    List<RemoteSpanLink> links();

    static SpanOptions child();

    static SpanOptions root();

    static SpanOptions detached();
}
```

```java
enum Topology { CHILD, ROOT, DETACHED }
```

**Topology / link compatibility matrix:**

| Topology | Parent behavior | Links allowed | Typical use | Enforcement |
|---|---|---:|---|---|
| CHILD | parent = current active span if present | forbidden in v1 | nested operation inside current trace | builder validates before start; `.linkedTo(...)` after `.child()` fails fast |
| ROOT | no parent, new trace | allowed | scheduled job, batch trigger, consumer root with upstream links | builder validates before start |
| DETACHED | no parent, new trace | forbidden | intentionally unlinked fire-and-forget/audit work | `linkedTo(...)` fails fast |

- `CHILD` — default, дочерний span.
- `ROOT` — новая trace; explicit links допустимы (scheduled job, batch trigger).
- `DETACHED` — новая trace без родителя; **links запрещены** (fail-fast при `.linkedTo(...)`).
- `DETACHED + linkedTo(...)` is invalid and must fail fast before span start.
- `SpanRelation` — **удалён полностью** из публичного и внутреннего кода.
- Links — только before start; post-start `addLink(...)` отсутствует в public API.

**v1 CHILD+links policy**

For v1:
- no builder opts into `CHILD + links`;
- Kafka consumer batch uses `ROOT + links`;
- `CHILD + links` remains a future extension requiring explicit ADR, documentation, and tests.

This keeps the initial topology policy simple:
- `ROOT + links` allowed;
- `DETACHED + links` forbidden;
- `CHILD + links` forbidden in v1.

Final policy summary:

- `ROOT + links` — allowed.
- `DETACHED + links` — forbidden (fail fast).
- `CHILD + links` — forbidden in v1.

### 3.8 PlatformSpanBuilder — terminal methods

Общий контракт semantic builders (`OperationSpanBuilder`, HTTP/DB/RPC/Kafka builders):

```java
public interface PlatformSpanBuilder<B extends PlatformSpanBuilder<B>> {

    B child();

    B root();

    B detached();

    B linkedTo(@Nonnull RemoteSpanLink... links);

    SpanHandle start();

    void run(@Nonnull Runnable action);

    <T> T call(@Nonnull Supplier<T> supplier);

    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;
}
```

Terminal methods (`run`/`call`/`callChecked`/`start`) — **первичный public path** для scoped execution. Нет public top-level `execute()`.

### 3.9 SpanHandle

```java
public interface SpanHandle extends AutoCloseable {

    void recordException(@Nullable Throwable throwable);

    @Override
    void close();
}
```

Application-командам рекомендуется `.run()`/`.call()`, не manual handle lifecycle.

### 3.10 Internal boundary (не public API)

```java
interface TracingImplementation {

    SpanHandle startSpan(SpanSpec spec);

    TraceContextView currentTraceContext();

    void recordException(SpanHandle span, Throwable throwable);

    TracingState state();
}
```

Conceptual state model (plan-level shape, not implementation):

```java
interface TracingState {

    TracingMode mode();

    Optional<String> reason();

    Map<String, String> details();
}
```

```java
enum TracingMode {
    ENABLED,
    DISABLED_BY_CONFIGURATION,
    UNAVAILABLE,
    NOOP,
    TEST
}
```

```text
TracingState replaces plain boolean isEnabled() for production supportability.
A boolean can tell whether tracing is enabled, but cannot explain why tracing is disabled, unavailable, degraded, no-op, or test-only.
```

```text
TracingState and TracingMode are internal supportability state types.
They are not application-facing public tracing API.
They belong to the internal/core implementation boundary unless Slice 2 explicitly decides otherwise.
They must not expose OpenTelemetry SDK types.
They must not become a span lifecycle API.
```

Diagnostics boundary rule:

`TracingDiagnostics` is the internal/Actuator-facing component or service that exposes platform tracing supportability state.
`TracingDiagnosticsView` is the stable DTO it returns.
These are two distinct types: the component must not expose internal `TracingState` directly; it must map internal `TracingState` to `TracingDiagnosticsView` suitable for Actuator, support tooling, and tests.

Conceptual DTO shape:

```java
public interface TracingDiagnosticsView {

    String mode();

    Optional<String> reason();

    Map<String, String> details();
}
```

This DTO is a diagnostics/supportability contract, not a span lifecycle API.
It must not expose OpenTelemetry SDK types.
Exact DTO name and field set are confirmed in Slice 7.

**Diagnostics DTO semantic stability**

The diagnostics DTO is a stable supportability contract.

Rules:
- internal `TracingState` must not be serialized directly;
- internal `TracingMode` additions must not automatically leak into Actuator JSON;
- unknown/future internal modes map to a stable fallback such as `"UNKNOWN"` unless explicitly approved by architecture review;
- adding a public diagnostics mode requires explicit diagnostics contract review;
- diagnostics DTO must not expose OpenTelemetry SDK types;
- diagnostics DTO JSON shape must have contract/snapshot tests.

Required tests:
- `DiagnosticsBoundaryTest`;
- diagnostics DTO JSON contract test.

Notes:

- This is an internal SPI, not application-facing API.
- It must be fully abstract: no default methods and no behavioral static helpers.
- `startSpan(SpanSpec)` is the single creation boundary used by operation builders, transport builders, `SpecifiedSpan`, metering, and no-op implementations.
- `currentTraceContext()` backs `PlatformTracing.traceContext()`.
- `recordException(...)` centralizes exception recording policy.
- `TracingState` replaces/preserves the current `facadeEnabled` kill-switch semantics and records the reason/state for diagnostics.

- `MeteredTracingImplementation` декорирует `TracingImplementation` полностью.
- `DefaultFacade(MeteredTracingImplementation(DefaultTracingImplementation))`.
- Optional internal `TracingExecutorImpl` — routes scoped execution through `TracingImplementation.startSpan()`; **never** exposed as `PlatformTracing.execute()`.

**Validation mode:** STRICT — production default. WARN — только local/dev profile. Без silent fallback в enabled-режиме.

### 3.11 Rejected names / old API (v2 and earlier)

Следующие имена **отклонены** и не должны появляться в public API:

| Rejected | Replacement |
|----------|-------------|
| `current()`, `CurrentTraceContext`, `currentTraceContext()` | `traceContext()`, `TraceContextView` |
| `businessSpan`, `internalSpan` (public entry) | `manual().operation(name)` |
| top-level `http()`/`db()`/`database()`/`rpc()`/`kafka()` on `PlatformTracing` | `manual().transport().http()/database()/rpc()/kafka()` |
| `execute()`, public `TracingExecutor` | terminal methods on builders / `SpecifiedSpan` |
| `AdvancedTracing`, `advanced()`, `rawSpan`, `raw()`, `escapeHatch`, `customSpan` | `manual().spanFromSpec(spec)` |
| `justification(String)` | `reason(SpanSpecReason)` + `reference(String)` — rejected: unstructured free text without governance value (see §3.6) |
| `ManualInstrumentation`, `manualInstrumentation`, `instrumented()`, `InstrumentedTracing`, `spans()` | `manual()`, `ManualTracing` |

---

## 3.12 Примеры целевого API

**Trace id:**

```java
String traceId = platformTracing.traceContext()
    .traceId()
    .orElse("unknown");
```

**Standard operation:**

```java
platformTracing.manual()
    .operation("recalculate-pricing")
    .run(() -> pricingService.recalculate(orderId));
```

**Returning value:**

```java
Price price = platformTracing.manual()
    .operation("calculate-price")
    .call(() -> pricingService.calculate(orderId));
```

**Checked exception:**

```java
Order order = platformTracing.manual()
    .operation("load-order")
    .callChecked(() -> repository.load(orderId));
```

**Scheduled root job:**

```java
platformTracing.manual()
    .operation("nightly-sync")
    .root()
    .run(() -> syncService.runNightlySync());
```

**Detached audit operation:**

```java
platformTracing.manual()
    .operation("write-audit-record")
    .detached()
    .run(() -> auditService.write(record));
```

**Kafka batch with links:**

```java
platformTracing.manual()
    .transport()
    .kafka()
    .consumer()
    .batch("orders")
    .root()
    .linkedTo(messageContexts)
    .run(() -> processor.processBatch(records));
```

**Manual database span:**

```java
platformTracing.manual()
    .transport()
    .database()
    .operation("SELECT")
    .system("postgresql")
    .collection("orders")
    .run(() -> unsupportedDriver.query(sql));
```

**Full spec-based span:**

```java
SpanSpec spec = SpanSpec.builder("legacy-protocol-call")
    .category(SpanCategory.INTERNAL)
    .root()
    .linkedTo(linkContext)
    .attribute("legacy.protocol", "x-proprietary")
    .reason(SpanSpecReason.UNSUPPORTED_PROTOCOL)
    .reference("PLATFORM-1234")
    .build();

platformTracing.manual()
    .spanFromSpec(spec)
    .run(() -> legacyClient.call(request));
```

---

## 4. Breaking Changes

| Удалённый API | Замена | Причина |
|--------------|--------|---------|
| `currentTraceId()` | `traceContext().traceId()` | Context access через read-only view |
| `currentSpanId()` | `traceContext().spanId()` | Context access через read-only view |
| `startSpan(name, category)` | Primary: `manual().operation(name).start()` / `.run()` / `.call()`<br>Governed exceptional: `manual().spanFromSpec(spec).start()` only when semantic builders do not cover the use case and `SpanSpec.reason()` governance is satisfied | Generic manual operation is the normal path; `spanFromSpec` is not a co-equal migration target |
| `startRootSpan` / `startDetachedSpan` / `startChildSpan` | `manual().operation(name).root().start()`<br>`manual().operation(name).detached().start()`<br>`manual().operation(name).child().start()` | Topology is explicit and terminal lifecycle is explicit; `detached().linkedTo(...)` remains invalid |
| `startSpan(name, category, SpanRelation)` | builder topology via `SpanOptions` | `SpanRelation` удалён полностью |
| `startSpanWithLinks` / `addLink` | builder `.linkedTo(...).start()` | Links must be pre-start |
| `inSpan(...)` × 4 overloads | builder `.run()` / `.call()` / `.callChecked()` | Default-методы с Javadoc-запретом |
| `advanced().rawSpan(...)` | `manual().spanFromSpec(spec)` | Governed spec path |
| top-level `http()/db()/rpc()/kafka()` | `manual().transport().http()/database()/rpc()/kafka()` | Transport grouped under `transport()` |
| `businessSpan(...)` / `internalSpan(...)` | `manual().operation(...)` | Application-level intent |
| **Typed start shortcuts** (`startHttpServer`, `startHttpClient`, `startDb`, `startRpcServer`, `startRpcClient`, `startInternal`) | `manual().operation(name)` или `manual().transport()...` | Span без mandatory semantic fields |
| **Builder factory methods** (`internalSpan`, `httpServerSpan`, `httpClientSpan`, `databaseSpan`, `rpcServerSpan`, `rpcClientSpan`, `kafkaProducerSpan`, `kafkaConsumerSpan`) | `manual().operation(...)` или `manual().transport()...` | Default factories возвращают validation-bypassing `Facade*` builders |
| 8 `Facade*SpanBuilder` + `AbstractFacadeTypedSpanBuilder` | Удалены в enabled-режиме | Bypass validation и anti-double guard |

---

## 5. Срезы

**Срез 0A — Baseline GREEN тесты (v1 API)**
- Цель: зафиксировать корректное поведение `DefaultPlatformTracing` для child/root/detached/links/addLink через текущий v1 API.
- Разрешено: только новые test-файлы в `platform-tracing-core/src/test`.
- Запрещено: любые изменения production-кода.
- Тесты: `DefaultPlatformTracingBaselineTest` — 5 методов. Фикстура per-test; `@AfterEach` assert `Span.current()` invalid; relative assertions only.
- **Lifecycle:** v1 baseline до Среза 1B. В Срезе 1B — переписать на v3 API (`manual().operation(...).root()`, `.linkedTo(...)`) или изолировать в non-default source set.
- Верификация: `.\gradlew.bat :platform-tracing-core:test --tests "*BaselineTest*"`.

**Срез 0B — RED тесты репро R01 (v1 API)**
- Цель: доказать деградацию `MeteredPlatformTracing` без изменения production-кода.
- Разрешено: test-файлы в core + autoconfigure; Gradle `knownDefectTest` + `excludeTags("r01-red")` — строго в тестовых целях.
- Запрещено: production-код; `@Disabled`.
- Тесты: `MeteredPlatformTracingKnownDefectTest` (4 RED methods covering relation-aware `startSpan`, `inSpan`, and links through Micrometer-enabled primary bean) + `TracingAutoConfigurationContextTest` с `FilteredClassLoader`.
- Slice 0B RED tests must also cover:
  - relation-aware `inSpan(..., SpanRelation.ROOT, ...)`;
  - relation-aware `inSpan(..., SpanRelation.DETACHED, ...)`;
  - `addLink(...)` through the Micrometer-enabled primary bean;
  - sanity assertion that the active bean path really uses the Micrometer-enabled decorator path and does not accidentally exercise `DefaultPlatformTracing` directly.
- Артефакт: `docs/known-issues/R01.md`.
- **Lifecycle:** v1 known-defect artifact до Среза 1B. Срез 6 добавляет **новые v3 metered topology tests** (не конвертация v1 RED).
- Верификация: `.\gradlew.bat :platform-tracing-core:knownDefectTest` (FAIL); `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:knownDefectTest` (FAIL); `.\gradlew.bat build` (GREEN).

**Срез 1A — API skeleton (аддитивный)**
- Цель: ввести новые sub-API интерфейсы рядом с существующим `PlatformTracing`.
- Additive interfaces: `TraceContextView`, `ManualTracing`, `OperationSpanBuilder`, `TransportTracing`, `HttpTracing`, `DatabaseTracing`, `RpcTracing`, `KafkaTracing`, `SpecifiedSpan`, `SpanSpec`, `SpanSpecBuilder` (with `.child()/.root()/.detached()/.linkedTo(...)` and typed attribute overloads that normalize to `SpanSpecAttributeValue`), `SpanSpecReason`, `SpanSpecAttributeValue`, `SpanOptions` (public immutable value model, not preferred builder grammar), `SpanHandle`, transport sub-builders.
- Запрещено: изменять `PlatformTracing.java`.
- **Forbidden stale public names in Slice 1A:**
  - `CurrentTraceContext`
  - `current()`
  - `currentTraceContext()`
  - `businessSpan`
  - `internalSpan` as public entry method
  - `ManualInstrumentation`
  - `manualInstrumentation`
  - `instrumented`
  - `InstrumentedTracing`
  - `spans`
  - `AdvancedTracing`
  - `advanced`
  - `escapeHatch`
  - `customSpan`
  - `rawSpan`
  - `raw`
  - `justification`
  - top-level `http`
  - top-level `db`
  - top-level `database`
  - top-level `rpc`
  - top-level `kafka`
  - public top-level `execute`
  - (some terms may appear only in §3.11 rejected/old API section)
- Тесты: ArchUnit — zero default/static **behavioral** methods on public facades, builders, and internal SPI; no OTel SDK leak.
- **ArchUnit rule nuance:**
  - Behavioral default methods are forbidden on public facades, builders, and internal SPI.
  - Behavioral static helpers are forbidden on public facades, builders, and internal SPI.
  - Static factories are allowed only on immutable value/spec types such as `SpanOptions`, `SpanSpec` builder/factory, or `SpanSpecAttributeValue` factories.
  - Static factories must not start spans, mutate context, access OpenTelemetry `Context`, record exceptions, or perform lifecycle work.
  - Examples allowed: `SpanOptions.child()`, `SpanOptions.root()`, `SpanOptions.detached()`, `SpanSpec.builder(...)`.
- **ArchUnit anti-skeleton rule**

For the internal SPI layer:
- behavioral default methods are forbidden;
- behavioral static helpers are forbidden;
- abstract skeleton classes with partial method bodies are forbidden.

Forbidden example:
`abstract class BaseTracingImplementation implements TracingImplementation`

Rationale:
An abstract class with partial implementations can recreate the same partial-delegation risk that caused R01, even without Java interface default methods.

Allowed:
- fully concrete implementations;
- pure interfaces;
- immutable value/spec static factories such as `SpanSpec.builder(...)`, `SpanOptions.root()`, and `SpanSpecAttributeValue` factories.
- Верификация: `.\gradlew.bat :platform-tracing-api:build`.

**Срез 1B — Атомарный cutover (multi-module)**
- Цель: переписать `PlatformTracing` → `traceContext()` + `manual()` only; удалить 9 `Facade*`/`AbstractFacade*`; удалить `SpanRelation`; обновить core + autoconfigure.
- Финальный commit/PR обязан быть GREEN.
- v1 тесты из 0A/0B: переписать на `manual().operation(...).root()` / `.linkedTo(...)` или non-default source set.
- **Pre-requisite: full removed-symbol inventory**

Before Slice 1B, attach a grep/report to the PR and review it manually.

The report must search all modules for:

- `currentTraceId`
- `currentSpanId`
- `startSpan`
- `startRootSpan`
- `startDetachedSpan`
- `startChildSpan`
- `startSpanWithLinks`
- `addLink`
- `inSpan`
- `startHttpServer`
- `startHttpClient`
- `startDb`
- `startRpcServer`
- `startRpcClient`
- `startInternal`
- `internalSpan`
- `httpServerSpan`
- `httpClientSpan`
- `databaseSpan`
- `rpcServerSpan`
- `rpcClientSpan`
- `kafkaProducerSpan`
- `kafkaConsumerSpan`
- `SpanRelation`
- public `recordException` path
- `MeteredPlatformTracing`
- `Facade*SpanBuilder`
- `AbstractFacadeTypedSpanBuilder`
- stale public names from §3.11 rejected names.

Every remaining occurrence must be classified:
- deleted;
- replaced;
- isolated as v1 characterization test;
- allowed only in rejected/old API documentation.
- Верификация: `.\gradlew.bat :platform-tracing-api:build :platform-tracing-core:build :platform-tracing-spring-boot-autoconfigure:build`.

**Срез 2 — Внутренняя граница реализации**
- Цель: `TracingImplementation` (minimal SPI per §3.10); refactor `DefaultPlatformTracing`; migrate anti-double guard; migrate `facadeEnabled` kill-switch to `TracingState`.
- ArchUnit permanent gate: `TracingImplementation` — zero default methods and zero behavioral static helpers (static factories on value/spec types remain allowed per §5 Slice 1A nuance); abstract skeleton SPI classes forbidden (see Slice 1A anti-skeleton rule).
- **Resolved direction:**
  - use `TracingImplementation.state()` returning internal `TracingState`;
  - do not use plain `boolean isEnabled()` as the internal supportability contract;
  - preserve current `facadeEnabled` semantics through `TracingState`;
  - keep `TracingState` / `TracingMode` inside the internal/core boundary unless explicitly changed by architecture review;
  - do not expose internal `TracingState` directly through Actuator;
  - Slice 7 maps `TracingState` to a stable diagnostics DTO.
- **Hard gate P1 — Bean topology / bean singularity**

Slice 2 must include named Spring context tests, e.g. `BeanTopologyTest`.

Required assertions:
- there is exactly one active `TracingImplementation` chain visible to the platform facade;
- when Micrometer is on the classpath, the active implementation is decorated by `MeteredTracingImplementation`;
- there is no public-facade metered decorator path;
- a competing `@Primary` / `@Order` `TracingImplementation` cannot silently bypass the metered chain;
- disabled/unavailable/no-op tracing still preserves a valid implementation chain and exposes `TracingState`.

This gate must be implemented in Slice 2, not deferred to Slice 7.
- **Hard gate P2 — Single span creation boundary**

Slice 2 must include named routing tests, e.g. `TracingImplementationRoutingTest`.

Required invariant:
all manual span creation paths route through `TracingImplementation.startSpan(SpanSpec)`.

This includes:
- `manual().operation(...)`;
- transport builders;
- `SpecifiedSpan`;
- `manual().spanFromSpec(spec)`;
- no-op implementation path;
- metered implementation path after decoration is introduced.

Forbidden:
- direct OpenTelemetry `Tracer` usage from public builders;
- direct span creation in `ManualTracing`;
- direct span creation in `SpecifiedSpan`;
- direct span creation in `MeteredTracingImplementation` except via delegation to the wrapped `TracingImplementation`.

This gate must be green before Slice 3A starts.

`TracingImplementationRoutingTest` is a core boundary test.
`BeanTopologyTest` is a Spring Boot autoconfiguration test because it uses Spring context, classpath conditions, Micrometer presence, and bean topology.

- Верификация:
  - `.\gradlew.bat :platform-tracing-core:test --tests "*TracingImplementationRoutingTest*"`
  - `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*BeanTopologyTest*"`

**Срез 3A — Operation + HTTP builders**
- Цель: `manual().operation(...)` + `OperationSpanBuilder`; HTTP under `manual().transport().http()`; STRICT production default.
- Тесты: `*OperationSpanBuilder*`, `*HttpSpanBuilder*`.
- Верификация: `.\gradlew.bat :platform-tracing-core:test --tests "*OperationSpanBuilder*" --tests "*HttpSpanBuilder*"`.

**Срез 3B — Database builder**
- Цель: `manual().transport().database()` с mandatory system/operation/collection; SQL sanitization.
- Верификация: `.\gradlew.bat :platform-tracing-core:test --tests "*DatabaseSpanBuilder*"`.

**Срез 3C — RPC/Kafka builders (versioned/RC)**
- Цель: `manual().transport().rpc()` / `.kafka()`; concrete semconv versioning (e.g. `@KafkaSemconvVersion`).
- Верификация: `.\gradlew.bat :platform-tracing-core:test --tests "*RpcSpanBuilder*" --tests "*KafkaSpanBuilder*"`.

**Срез 4 — Scoped execution terminal methods**
- Цель: `run`/`call`/`callChecked`/`start` на `OperationSpanBuilder`, `PlatformSpanBuilder`, `SpecifiedSpan`; exactly-once exception recording policy; internal routing через `TracingImplementation.startSpan()` (optional internal `TracingExecutorImpl`).
- Запрещено: public top-level `execute()`; reactive overloads.
- **Exactly-once policy:** suppress duplicate same-Throwable via scoped path; allow explicit `recordException`; allow two different Throwables; no global suppression after first event.
- Тесты: builder + `SpecifiedSpan` lifecycle; three exception scenarios; nested LIFO; ArchUnit `Publisher` rejection; ArchUnit SPI routing.
- Верификация: `.\gradlew.bat :platform-tracing-core:test --tests "*OperationSpanBuilder*" --tests "*SpecifiedSpan*" --tests "*ScopedExecution*"`.

**Срез 5A — SpanOptions core topology**
- Цель: `manual().operation(...).root()/.detached()/.child()`; `Topology` enum; DETACHED forbids links.
- Верификация: `.\gradlew.bat :platform-tracing-core:test --tests "*SpanOptions*"`.

**Срез 5B — Links-before-start и Kafka batch**
- Цель: `linkedTo()`, W3C traceparent parsing, `manual().transport().kafka().consumer().batch(...).root().linkedTo(...)` (v1: Kafka batch uses ROOT+links, not CHILD+links).
- Верификация: `.\gradlew.bat :platform-tracing-core:test --tests "*KafkaConsumer*" --tests "*RemoteContext*"`.

**Срез 6 — Redesign decorator/metering**
- Цель: `MeteredTracingImplementation` decorates `TracingImplementation` fully; **новые v3 metered topology tests**.
- Тесты: `MeteredTopologyMatrixTest`. **Slice 6 v3 metered topology tests must cover:**
  - `manual().operation(...).root().linkedTo(...)` only for documented batch/internal root use cases;
  - `manual().operation(...).detached()` without links;
  - `manual().operation(...).detached().linkedTo(...)` must fail fast;
  - `manual().transport().kafka().consumer().batch(...).root().linkedTo(...)`;
  - `manual().spanFromSpec(spec)` with ROOT + links and DETACHED without links according to `SpanOptions` policy.
- Kafka batch remains the primary public example for links.
- Metric-count vs topology in separate methods (R12).
- Верификация: `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*MeteredTopologyMatrixTest*" --tests "*MetricsCount*"`.

**Срез 7 — Spring Boot auto-configuration и diagnostics**
- Цель: primary `PlatformTracing` bean; no-op for disabled/unavailable; `TracingDiagnostics` Actuator endpoint.
- `TracingDiagnostics` must expose a stable diagnostics DTO mapped from internal `TracingState`.
- It must include mode/reason/details suitable for support diagnostics, without exposing OpenTelemetry SDK types.
- It must not expose internal `TracingState` directly unless architecture review explicitly decides to make `TracingState` a public supportability API.
- Slice 7 extends the Spring Boot matrix started in Slice 2 (`BeanTopologyTest`); Slice 7 is not the first place where bean topology is structurally proven.
- Slice 7 must include `ObservationCoexistenceTest` if Micrometer Observation remains enabled.

Required assertion:
one application operation / HTTP request must not produce two unsynchronized root spans through separate Spring Observation and PlatformTracing paths.

The exact behavior depends on the ADR selected before Slice 1A.
- Тесты: `ApplicationContextRunner` matrix с `FilteredClassLoader`; webmvc/webflux/TracedAspect against new facade; `DiagnosticsBoundaryTest`; diagnostics DTO JSON contract test; `ObservationCoexistenceTest`.
- Верификация: `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*DiagnosticsBoundaryTest*" --tests "*ObservationCoexistenceTest*"`.

**Срез 8 — Docs и примеры**
- Цель: getting-started; compilable sample module; ADRs.
- ADR topics:
  - why `traceContext()` over `current()`/`currentContext()`
  - why `manual()` is acceptable (counterpart to auto-instrumentation)
  - why `operation(name)` replaced `internalSpan`/`businessSpan`
  - why transport grouped under `transport()`
  - why `spanFromSpec(spec)` replaced `advanced`/`rawSpan`/`escapeHatch`
  - why `reason(SpanSpecReason)` + `reference` replaced `justification`
  - ROOT/DETACHED semantic decision
  - SpanSpec governance policy
  - Relationship to Micrometer Observation API
- Верификация: `.\gradlew.bat :platform-tracing-samples:compileJava`.

---

## 6. Стратегия тестирования

Required named test suites:

- `DefaultPlatformTracingBaselineTest` — Slice 0A legacy behavior.
- `MeteredPlatformTracingKnownDefectTest` — Slice 0B R01 RED.
- `BeanTopologyTest` — Slice 2 minimal Spring Boot autoconfiguration bean singularity proof; lives in `platform-tracing-spring-boot-autoconfigure`.
- `TracingImplementationRoutingTest` — Slice 2 core single creation boundary proof; lives in `platform-tracing-core`.
- `SpanSpecBuilderFinalStateTest` — Slice 1A/5A builder final-state and repeated setter semantics.
- `SpanSpecGovernanceTest` — Slice 4/5 runtime governance.
- `DiagnosticsBoundaryTest` — Slice 7 diagnostics DTO boundary.
- `MeteredTopologyMatrixTest` — Slice 6 decorator topology preservation.
- `ObservationCoexistenceTest` — Slice 7 Micrometer Observation coexistence / duplicate-span prevention.

**Bean testing responsibility split**

- Slice 2 `BeanTopologyTest` is the minimal structural bean-chain proof:
  - one active `TracingImplementation` chain;
  - Micrometer classpath produces `MeteredTracingImplementation`;
  - no public-facade metered decorator path;
  - competing `@Primary` / `@Order` implementation cannot silently bypass the chain.

- Slice 7 Spring Boot matrix extends this proof to the full autoconfiguration surface:
  - enabled/disabled/unavailable modes;
  - servlet/webflux/aspect combinations;
  - diagnostics endpoint;
  - `FilteredClassLoader` scenarios;
  - `ObservationCoexistenceTest`;
  - diagnostics DTO contract.

- Срез 0A: v1 baseline tests до cutover. Permanent guard = semantic invariants, not specific test classes.
- Срез 0B: v1 known-defect; `@Tag("r01-red")`; `knownDefectTest` in core + autoconfigure. Срез 6 → v3 metered topology tests.
- Срез 2: ArchUnit — `TracingImplementation` без defaults/behavioral static helpers (static factories on value/spec types allowed per Slice 1A nuance).
- Срезы 3A-3C: validation per semconv domain.
- Срез 4: exactly-once exception recording (structural).
- Срезы 5A/5B: topology vs wire-format.
- Срез 6: v3 topology tests; R12 separate count vs topology methods.
- Срез 7: genuine classloader filtering.

**Fixture invariant:** per-test `SdkTracerProvider`+`InMemorySpanExporter`+`SimpleSpanProcessor`; `@AfterEach` `Span.current()` invalid; lookup by name/attribute.

---

## 7. Таблица верификации

| Срез | Команда (Windows) | Ожидается |
|------|-------------------|-----------|
| 0A | `.\gradlew.bat :platform-tracing-core:test --tests "*BaselineTest*"` | GREEN |
| 0B | `.\gradlew.bat :platform-tracing-core:knownDefectTest` | FAILS (документировано) |
| 0B | `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:knownDefectTest` | FAILS (документировано) |
| 1A | `.\gradlew.bat :platform-tracing-api:build` | GREEN |
| 1B | `.\gradlew.bat :platform-tracing-api:build :platform-tracing-core:build :platform-tracing-spring-boot-autoconfigure:build` | GREEN (atomic) |
| 2 | `.\gradlew.bat :platform-tracing-core:test --tests "*TracingImplementationRoutingTest*"`<br>`.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*BeanTopologyTest*"` | GREEN |
| 3A-3C | `.\gradlew.bat :platform-tracing-core:test --tests "*SpanBuilder*"` | GREEN per sub-slice |
| 4 | `.\gradlew.bat :platform-tracing-core:test --tests "*OperationSpanBuilder*" --tests "*SpecifiedSpan*" --tests "*SpanSpecGovernanceTest*"` | GREEN |
| 5A-5B | `.\gradlew.bat :platform-tracing-core:test --tests "*SpanSpecBuilderFinalStateTest*" --tests "*SpanOptions*" --tests "*KafkaConsumer*"` | GREEN |
| 6 | `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*MeteredTopologyMatrixTest*" --tests "*MetricsCount*"` | v3 topology tests GREEN |
| 7 | `.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*DiagnosticsBoundaryTest*" --tests "*ObservationCoexistenceTest*"` | GREEN |
| 8 | `.\gradlew.bat :platform-tracing-samples:compileJava` | GREEN |
| All | `.\gradlew.bat build` | GREEN на каждой границе, кроме Slice 1B local workspace exception |

---

## 8. Реестр рисков

> Risk IDs R04–R06, R09, and R11 were merged or retired during earlier plan reviews; they are intentionally omitted from this register.

| ID | Риск | Severity | Срез | Mitigation |
|----|------|----------|------|------------|
| R01 | Metered decorator деградирует ROOT/DETACHED/links | Critical | 0B→6 | Decoration на pure-abstract internal boundary |
| R02 | `Facade*`+`AbstractFacade*SpanBuilder` bypass validation | High | 1B, 3 | Удалены; builder factories abstract |
| R03 | `spanFromSpec` станет dumping ground | High | 4-5 | Mandatory `SpanSpecReason`, runtime STRICT validation, valid reference policy for temporary workarounds, diagnostics by reason/reference, and platform ArchUnit/build-plugin guidance |
| R07 | Metrics double-count | High | 6 | Single boundary in `MeteredTracingImplementation` |
| R08 | Scoped execution spans bypass metering | High | 4, 6 | Internal routing via `TracingImplementation.startSpan()`, ArchUnit |
| R10 | Kafka/RPC semconv instability | Medium | 3C | Concrete versioning annotation |
| R12 | Metric counters маскируют topology defects | Medium | 0B, 6 | Separate test methods |
| R13 | Kill-switch `facadeEnabled` теряется или становится недиагностируемым | Medium | 2 | Migrate to `TracingImplementation.state()` / `TracingState`; preserve mode/reason for diagnostics |
| R14 | Untyped SpanSpec attributes become a dumping ground for invalid values, PII, high cardinality, or unsupported OTel attribute types | High | 1A-5 | `SpanSpecAttributeValue` whitelist + typed `SpanSpecBuilder` overloads + no `attribute(String, Object)` + `AttributePolicy` validation + STRICT fail-fast |
| R15 | `TEMPORARY_WORKAROUND` becomes permanent | Medium | 4-8 | `reference()` required + diagnostics + platform review + docs + periodic audit of active references via diagnostics/review gate |
| R16 | `SpanSpec` examples expose confusing topology API (`.topology(SpanOptions...)` / `.options(SpanOptions...)`) | Low | 1A | Public builder grammar uses `.child()/.root()/.detached()/.linkedTo(...)` and typed attribute overloads on `SpanSpecBuilder`; validation is final-state based; `SpanOptions` is a public immutable value model returned by `SpanSpec.options()`, not the preferred builder grammar |
| R17 | `CHILD + links` policy remains ambiguous | Medium | 5A/5B | `CHILD + links` forbidden in v1; Kafka batch uses ROOT+links; future CHILD+links requires ADR |
| R18 | `SpanSpecReason` enum becomes a dumping ground via `OTHER`/`UNKNOWN`/`CUSTOM`/`MISC` values | Medium | 1A/8 | New reasons require architecture approval/ADR; generic catch-all values forbidden |
| R19 | Plain enabled/disabled state hides why tracing is unavailable or no-op | Medium | 2,7 | Use internal `TracingState` with mode/reason/details; expose a mapped diagnostics DTO without OTel SDK leak |
| R20 | Internal `TracingState` leaks as unstable public Actuator/API contract | Medium | 7 | Map internal state to stable diagnostics DTO with semantic stability rule; DTO contract/snapshot tests; do not expose OTel SDK or internal implementation types |
| R21 | Abstract skeleton SPI class recreates partial-delegation risk | High | 1A,2 | ArchUnit forbids abstract classes with method bodies implementing `TracingImplementation`; only full concrete implementations are allowed |
| R22 | Spring bean graph bypasses `MeteredTracingImplementation` despite new SPI | Critical | 2,6,7 | `BeanTopologyTest` proves bean singularity and metered chain under Micrometer |
| R23 | Builders create spans outside `TracingImplementation.startSpan(SpanSpec)` | Critical | 2-6 | `TracingImplementationRoutingTest` and ArchUnit enforce single creation boundary |
| R24 | Micrometer Observation and PlatformTracing create duplicate or inconsistent spans | High | 1A,7 | ADR before Slice 1A; `ObservationCoexistenceTest` in Slice 7 |
| R25 | Diagnostics DTO becomes accidental passthrough of internal `TracingState` | Medium | 7 | Semantic stability rule and DTO contract/snapshot tests |

---

## 9. Open Questions

**Нет blocking вопросов перед Срезами 0A/0B** — public API surface, ROOT/DETACHED, `SpanRelation` fate, naming решены (v3 approved doc).

**Before Slice 1A — architect review checklist (v3.4.2 clarifications):**

- [x] `SpanSpecAttributeValue` model accepted.
- [x] `SpanSpecAttributeValue` invariants accepted (immutability, no-null, defensive-copy lists, single-type lists, STRICT fail-fast).
- [x] `SpanSpecAttributeValue` preferred implementation shape accepted: sealed interface or final immutable value classes/factories.
- [x] `SpanSpecBuilder` public grammar accepted: `.child()`, `.root()`, `.detached()`, `.linkedTo(...)` directly on the builder, including final-state validation independent of call order; `SpanOptions` is a public immutable value model, not the preferred builder grammar.
- [x] `SpanSpecBuilder` attribute overload policy accepted:
  - typed scalar/list overloads;
  - no `attribute(String, Object)`;
  - all values normalized into `SpanSpecAttributeValue`.
- [x] `SpanSpecReason` governance accepted (including `TEMPORARY_WORKAROUND` rules and forbidden `OTHER`/`UNKNOWN`/`CUSTOM`/`MISC` catch-all values).
- [x] Topology/link compatibility matrix accepted, including v1 `CHILD + links` forbidden policy.
- [ ] `TracingImplementation` minimal SPI accepted.
- [x] ArchUnit static-factory nuance accepted.
- [x] Mandatory platform `spanFromSpec` governance accepted.
- [x] Micrometer Observation API ADR accepted.
- [x] Architect sign-off on repeated setter policy.
- [x] Architect sign-off on CHILD+links v1 policy.
- [x] Architect sign-off on ArchUnit anti-skeleton rule.

**Before Slice 2 — architect review checklist:**

- [ ] Confirm exact internal `TracingState` / `TracingMode` values.
- [ ] Confirm whether they remain package-private/internal or become public supportability types.
- [ ] `BeanTopologyTest` scope accepted.
- [ ] `TracingImplementationRoutingTest` scope accepted.
- [ ] single creation boundary proof accepted as Slice 2 hard gate.

**Before Slice 6/7 — architect review checklist:**

- [ ] `MeteredTopologyMatrixTest` scope accepted.
- [ ] `ObservationCoexistenceTest` scope accepted.
- [ ] diagnostics DTO semantic stability / snapshot contract accepted.

**Before Slice 7 — architect review checklist:**

- [ ] Confirm diagnostics DTO name and field set.
- [ ] Confirm Actuator contract maps from internal `TracingState` and does not expose OTel SDK types.

**Blocking перед Срезом 1B:**
- Full removed-symbol inventory report attached to PR and manually reviewed (see Slice 1B prerequisite).

**Можно отложить (Срез 8 или позже):**
- `TraceContextView.sampled()` — additive when consumer identified.
- Reactive scoped execution roadmap — out of scope v1.
- Actuator vs JMX beyond v1 Actuator-only.
- Exception sanitization policy sign-off.
- Performance/JMH benchmark baseline.

---

## 10. Cursor Composer Prompt Roadmap

| Срез | Цель |
|------|------|
| 0A | Baseline GREEN tests для `DefaultPlatformTracing` (v1 API) |
| 0B | `@Tag("r01-red")` RED tests + `knownDefectTest` + `docs/known-issues/R01.md` |
| 1A | Additive API skeleton + ArchUnit anti-skeleton rule + `SpanSpecBuilderFinalStateTest` scope |
| 1B | Cutover: `PlatformTracing` → `traceContext()` + `manual()`; delete Facade* + `SpanRelation`; full removed-symbol inventory |
| 2 | `TracingImplementation`, `TracingState`, `TracingImplementationRoutingTest` (core), `BeanTopologyTest` (autoconfigure), anti-double guard, kill-switch migration |
| 3A | `manual().operation(...)` + `manual().transport().http()`; STRICT validation |
| 3B | `manual().transport().database()` |
| 3C | `manual().transport().rpc()/kafka()`; semconv versioning |
| 4 | Terminal methods on builders/`SpecifiedSpan`; exactly-once recording; `SpanSpecGovernanceTest` |
| 5A | `SpanOptions` topology; ROOT/DETACHED distinction; `SpanSpecBuilderFinalStateTest` |
| 5B | Links-before-start; Kafka batch ROOT+links |
| 6 | `MeteredTracingImplementation`; `MeteredTopologyMatrixTest` |
| 7 | Spring wiring, `TracingDiagnostics` DTO, `DiagnosticsBoundaryTest`, `ObservationCoexistenceTest`, `ApplicationContextRunner` matrix |
| 8 | Docs, ADRs (naming + SpanSpec governance + Micrometer Observation boundary), sample module |

---

## 11. Финальная рекомендация

Slice 0A and Slice 0B may start immediately.

Slice 1A ADR gate status:
- Micrometer Observation API ADR accepted: yes (2026-07-06).
- Slice 1A gate decisions approved: yes (2026-07-06).
- Slice 1A may start (additive API skeleton only).

Slice 1B must still wait for:
- full removed-symbol inventory report;
- Slice 1A complete and `TracingImplementation` minimal SPI sign-off before Slice 2.

Slice 2 must include hard proof gates:
- `BeanTopologyTest`;
- `TracingImplementationRoutingTest`.

Slice 6/7 must not claim R01 is fixed until:
- `MeteredTopologyMatrixTest` is green;
- Spring bean topology tests are green;
- diagnostics DTO contract tests are green;
- Observation coexistence behavior is tested according to the ADR.

Public API — v3.4.2 model: `traceContext()` + `manual()` с `operation` / `transport` / `spanFromSpec`; public `SpanSpec.builder(...)` использует `.child()/.root()/.detached()/.linkedTo(...)` и typed attribute overloads; `SpanOptions` — public immutable value model, не preferred builder grammar; internal state — `TracingImplementation.state()` / internal `TracingState`; Actuator — mapped diagnostics DTO, not internal state directly. Косметический патч `MeteredPlatformTracing` отклонён. Единственный durable fix — internal abstract SPI boundary `TracingImplementation` (Срез 2, завершение Срез 6).
