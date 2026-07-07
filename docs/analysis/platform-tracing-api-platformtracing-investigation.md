# PlatformTracing Investigation Report

> **Scope:** read-only source investigation of `space.br1440.platform.tracing.api.PlatformTracing` and directly adjacent code.  
> **Purpose:** standalone context for Perplexity / multi-LLM architecture review and future refactoring design.  
> **Date:** 2026-07-04  
> **Repository:** `E:\Platform_Traces`

---

## 1. Executive Summary

`PlatformTracing` is the **single public entry point** for application developers who want to manually create and enrich spans. It lives in the **`platform-tracing-api`** module as a Java interface with a small abstract core (4 methods) and a large default-method surface (convenience APIs, typed builders, `inSpan` lifecycle helpers). Production behavior is provided by **`DefaultPlatformTracing`** (`platform-tracing-core`) on top of OpenTelemetry `Tracer` + `Context`. Spring Boot wiring is in **`TracingCoreAutoConfiguration`**, optionally wrapped by **`MeteredPlatformTracing`** when Micrometer `MeterRegistry` is present.

### What is confirmed

- **Confirmed:** `PlatformTracing` is an interface; only `currentTraceId()`, `currentSpanId()`, `startSpan(String, SpanCategory)`, and `recordException(Throwable)` are abstract (`PlatformTracing.java:34–60`, `253`).
- **Confirmed:** All relation-aware span creation (`SpanRelation`, links) is implemented in `DefaultPlatformTracing` (`DefaultPlatformTracing.java:159–184`, `207–231`), **not** in interface defaults — defaults explicitly degrade to `startSpan(name, category)` and ignore relation/links (`PlatformTracing.java:69–77`, `108–115`, `118–122`).
- **Confirmed:** Typed builder escape-hatches have **facade fallbacks** in API (`Facade*SpanBuilder`) and **policy-backed implementations** in core (`*SpanBuilderImpl`); `DefaultPlatformTracing` overrides all builder factory methods (`DefaultPlatformTracing.java:87–137`).
- **Confirmed:** `inSpan(...)` is entirely default-method based; Javadoc forbids overriding it in decorators to avoid double metric counting (`PlatformTracing.java:256–268`).
- **Confirmed:** Comprehensive unit tests exist for `DefaultPlatformTracing` span relations, links, and `inSpan` lifecycle in `platform-tracing-core`; Spring auto-configuration tests cover bean selection and `MeteredPlatformTracing` decoration.
- **Confirmed (critical):** When `MeteredPlatformTracing` is the injected `@Primary` bean, **interface default methods for `SpanRelation` and links resolve on the decorator**, which does **not** override `startSpan(name, category, relation)`, `startSpanWithLinks`, or `addLink`. This causes relation/links semantics to silently degrade to `CHILD` / no links in production apps with Micrometer — see §7 and Risk R-01.

### What is uncertain

- **Requires runtime verification:** Whether any production service currently relies on `startRootSpan` / `startSpanWithLinks` / `addLink` through Spring-injected `PlatformTracing` with Micrometer enabled (would be broken if so).
- **Requires architecture decision:** Whether `MeteredPlatformTracing` should delegate all default-method entry points, or whether relation/link methods should be promoted from default to abstract/overridden.
- **Missing test:** No test combines `MeteredPlatformTracing` + `SpanRelation.ROOT` / links (gap confirmed by source inspection).

### Biggest refactoring risks

1. **Decorator + interface default-method dispatch** breaks `SpanRelation` and links when `MeteredPlatformTracing` is primary (Critical).
2. **Public interface evolution** — many default methods; changing behavior is source-compatible but may break implicit contracts (High).
3. **Facade fallback vs policy-backed divergence** — links, semantic validation, anti-double guard differ by implementation (High).
4. **Reactive boundary** — no `inSpan(Mono/Flux)`; misuse of sync API around reactive assembly documented as anti-pattern (Medium–High).
5. **Escape-hatch builders + OTel Agent** — double instrumentation if used without guards (High, mitigated by ArchUnit + runtime anti-double guard).

### Best next steps

1. Add characterization tests for `MeteredPlatformTracing` + `SpanRelation` / links before any refactor.
2. Send Perplexity questions (§13) on decorator/default-method patterns and OTel link semantics for batch consumers.
3. Inventory production usages of `startRootSpan`, `startSpanWithLinks`, `addLink` (grep + runtime telemetry).
4. Run verification gate commands (§9) on Windows Gradle before refactoring.

---

## 2. Primary Class Overview

| Property | Value |
|----------|-------|
| **File** | `platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/PlatformTracing.java` |
| **Package** | `space.br1440.platform.tracing.api` |
| **Module** | `platform-tracing-api` |
| **Type** | `public interface PlatformTracing` |
| **Lines** | 359 (as inspected) |

### Stated intent (Javadoc summary)

- Single public facade for manual span creation/enrichment (`PlatformTracing.java:16–28`).
- Implementation lives in `platform-tracing-core`; Spring bean from `platform-tracing-spring-boot-autoconfigure`.
- Typed methods map to platform-required categories: HTTP server/client, database, RPC, internal, Kafka producer/consumer.
- `currentTraceId()` / `currentSpanId()` for MDC, response headers, error models.
- `SpanScope` **must** be closed (try-with-resources); unclosed scopes leak OTel ThreadLocal context (`PlatformTracing.java:49–57`).

### Public methods grouped by category

| Category | Methods |
|----------|---------|
| **Context lookup** | `currentTraceId()`, `currentSpanId()` |
| **Abstract span creation** | `startSpan(String, SpanCategory)` |
| **Relation-aware creation (defaults)** | `startSpan(String, SpanCategory, SpanRelation)`, `startRootSpan`, `startChildSpan`, `startDetachedSpan`, `startSpanWithLinks`, `addLink` |
| **Typed convenience (defaults)** | `startHttpServer`, `startHttpClient`, `startDb`, `startRpcServer`, `startRpcClient`, `startInternal` |
| **Builder factories (defaults)** | `internalSpan`, `httpServerSpan`, `httpClientSpan`, `databaseSpan`, `rpcServerSpan`, `rpcClientSpan`, `kafkaProducerSpan`, `kafkaConsumerSpan` |
| **Exception on active span** | `recordException(Throwable)` (abstract) |
| **High-level lifecycle (defaults)** | `inSpan` × 4 overloads (Runnable / ThrowingSupplier × with/without SpanRelation) |

### API stability observations

- **4 abstract methods** — binary-compatible evolution requires keeping these stable.
- **~30 default methods** — new defaults can be added without breaking implementors; changing existing default bodies changes behavior for any implementation that does not override them (`NoOpPlatformTracing`, `MeteredPlatformTracing`).
- **Nullability:** Jakarta `@Nonnull` / `@Nullable` on parameters and returns; `inSpan` uses `Objects.requireNonNull` on actions/suppliers (`PlatformTracing.java:284`, `311`, `331`, `349`).
- **No checked exceptions** on interface except `inSpan(..., ThrowingSupplier)` declares `throws Exception` (`PlatformTracing.java:308–310`).
- **Dependencies:** API module uses `compileOnly` OTel API/context (`platform-tracing-api/build.gradle:16–21`); runtime OTel supplied by agent/starter/core.

---

## 3. Public API Surface

### Master method table

| Method | Kind | Returns | Throws | Default/abstract | Main responsibility | Risk |
|--------|------|---------|--------|------------------|---------------------|------|
| `currentTraceId()` | abstract | `Optional<String>` | — | abstract | Hex trace id of active OTel span | Low |
| `currentSpanId()` | abstract | `Optional<String>` | — | abstract | Hex span id of active OTel span | Low |
| `startSpan(name, category)` | abstract | `SpanScope` | — | abstract | Create span (impl defines relation default) | **Medium** (must close scope) |
| `startSpan(name, category, relation)` | default | `SpanScope` | — | default → `startSpan(name, category)` | Relation-aware creation | **High** (ignored in default; decorator bug) |
| `startRootSpan(name, category)` | default | `SpanScope` | — | default | New trace, no parent | **High** (via relation default chain) |
| `startChildSpan(name, category)` | default | `SpanScope` | — | default | Child of active context | Low (matches 2-arg default path) |
| `startDetachedSpan(name, category)` | default | `SpanScope` | — | default | No parent, no auto links | **High** (relation ignored in default) |
| `startSpanWithLinks(name, category, links)` | default | `SpanScope` | — | default → `startSpan(name, category)` | Span with remote links | **Critical** (links ignored in default) |
| `addLink(link)` | default | `void` | — | default no-op | Add link to active span | **Critical** (no-op in default) |
| `startHttpServer(name)` | default | `SpanScope` | — | default | `HTTP_SERVER` shortcut | Low |
| `startHttpClient(name)` | default | `SpanScope` | — | default | `HTTP_CLIENT` shortcut | Low |
| `startDb(name)` | default | `SpanScope` | — | default | `DATABASE` shortcut | Low |
| `startRpcServer(name)` | default | `SpanScope` | — | default | `RPC_SERVER` shortcut | Low |
| `startRpcClient(name)` | default | `SpanScope` | — | default | `RPC_CLIENT` shortcut | Low |
| `startInternal(name)` | default | `SpanScope` | — | default | Via `internalSpan().name(name).start()` | Low |
| `internalSpan()` | default | `InternalSpanBuilder` | — | default → `FacadeInternalSpanBuilder` | Policy builder or fallback | **Medium** (fallback lacks validation) |
| `httpServerSpan()` … `kafkaConsumerSpan()` | default | `*SpanBuilder` | — | default → `Facade*` | Escape-hatch typed builders | **High** (double instrumentation) |
| `recordException(throwable)` | abstract | `void` | — | abstract | Record on active span | Low (no-op if no span in impl) |
| `inSpan(name, category, Runnable)` | default | `void` | `RuntimeException` | default | Sync lifecycle wrapper | **Medium** (reactive misuse) |
| `inSpan(name, category, ThrowingSupplier<T>)` | default | `T` | `Exception` | default | Sync lifecycle + return value | **Medium** (lambda overload UX) |
| `inSpan(name, category, relation, Runnable)` | default | `void` | `RuntimeException` | default | ROOT/DETACHED via relation | **High** (relation chain) |
| `inSpan(name, category, relation, ThrowingSupplier<T>)` | default | `T` | `Exception` | default | Same + return | **High** |

### Overload families

1. **`startSpan`:** 2-arg (abstract) vs 3-arg with `SpanRelation` (default).
2. **`inSpan`:** Runnable vs `ThrowingSupplier<T>` vs same with `SpanRelation`.
3. **Builders:** each category has interface + `Facade*` fallback + `*Impl` in core.

### Generic methods

- `inSpan(..., ThrowingSupplier<? extends T> supplier)` — only generic method (`PlatformTracing.java:308–319`).

### Lambda overload ambiguity

- `inSpan(name, category, ThrowingSupplier)` vs Runnable: if lambda throws no checked exceptions, both are applicable; `ThrowingSupplier` is the value-returning overload. Javadoc documents Callable method-reference pattern (`PlatformTracing.java:304–306`).
- No `Callable` overload exists — by design (uses `ThrowingSupplier` from API module, Spring-independent: `ThrowingSupplier.java:1–18`).

---

## 4. Span Lifecycle Model

### Starting a span

**Path A — procedural (`startSpan`):**

```text
startSpan(name, category)
  → [DefaultPlatformTracing] startSpan(name, category, CHILD)
  → startSpanInternal(name, category, relation, links=[])
  → if !facadeEnabled → NoOpSpanScope.INSTANCE
  → tracer.spanBuilder(name).setSpanKind(...).setAttribute(platform.trace.type, ...)
  → if ROOT|DETACHED → setParent(Context.root())
  → for each link → builder.addLink(remoteContext)
  → span.startSpan(); scope = span.makeCurrent()
  → return OwningSpanScope(span, scope, exceptionRecorder)
```

Evidence: `DefaultPlatformTracing.java:155–231`, `OwningSpanScope.java:39–44`.

**Path B — builder (`internalSpan().…start()`):**

```text
AbstractPlatformSpanBuilder.startSpanInternal()
  → anti-double guard (re-entry same category → NonOwningSpanScope.enrich)
  → policy.validateAndNormalize → span name from semconv rules
  → tracer.spanBuilder(spanName).setSpanKind.setAllAttributes.startSpan()
  → Context.with(span).with(PLATFORM_SPAN_CATEGORY marker).makeCurrent()
  → return OwningSpanScope
```

Evidence: `AbstractPlatformSpanBuilder.java:105–144`.

**Path C — facade fallback builder:**

```text
AbstractFacadeTypedSpanBuilder.start()
  → tracing.startSpan(name, category)  // no semconv validation
  → apply pending attributes to SpanScope
```

Evidence: `AbstractFacadeTypedSpanBuilder.java:91–97`.

### Becoming current

- Procedural path: `span.makeCurrent()` (OTel `Scope`) — `DefaultPlatformTracing.java:228–229`.
- Builder path: explicit `Context.current().with(span).with(category marker)` — `AbstractPlatformSpanBuilder.java:132–135`.

### Closing

```text
try (SpanScope scope = ...) { ... }
  → SpanScope.close()
  → [OwningSpanScope] compareAndSet closed; scope.close(); span.end()
  → idempotent on repeat close
```

Evidence: `OwningSpanScope.java:109–125`, `SpanScope.java:61–65`.

### Exception recording

| Entry point | Behavior |
|-------------|----------|
| `PlatformTracing.recordException(t)` | `ExceptionRecorder.record(Span.current(), t)` — no-op if no valid span (`DefaultPlatformTracing.java:186–191`, `ExceptionRecorder.java:40–43`) |
| `SpanScope.recordException(t)` | Same via `ExceptionRecorder` on owned span (`OwningSpanScope.java:91–97`) |
| `inSpan` on RuntimeException | `scope.recordException(e); throw e` (`PlatformTracing.java:288–290`) |
| `inSpan` on checked Exception | `scope.recordException(e); throw e` (`PlatformTracing.java:315–317`) |

`ExceptionRecorder` sets `error.type`, `platform.trace.result=failure`, sanitized `exception` event, `StatusCode.ERROR` (`ExceptionRecorder.java:45+`).

### Happy path

- Span created, made current, business logic runs, scope closed in try-with-resources finally → span exported (if sampled).

### Runtime exception path

- `inSpan`: caught, recorded on scope, rethrown; scope still closed by try-with-resources.
- Manual: caller must invoke `scope.recordException(e)` (documented in `SpanScope.java:19–21`).

### Checked exception path

- Only via `inSpan(..., ThrowingSupplier)` — propagates `Exception` after recording.

### No-op by default

- `addLink` — interface default empty body (`PlatformTracing.java:120–122`).
- `NoOpPlatformTracing` / `NoOpSpanScope` — all mutations no-op (`NoOpPlatformTracing.java:38–52`, `NoOpSpanScope.java:29–74`).
- `recordException` on `NoOpPlatformTracing` — empty (`NoOpPlatformTracing.java:38–40`).
- Facade kill-switch: `DefaultPlatformTracing.setFacadeEnabled(false)` → `NoOpSpanScope` (`DefaultPlatformTracing.java:212–215`).

### Context leak risks

- **Unclosed `SpanScope`:** Javadoc warns ThreadLocal leak (`PlatformTracing.java:56–57`, `SpanScope.java:26–28`).
- **Reactive misuse:** closing span before subscription (`PlatformTracing.java:266–268`, ADR-reactor).
- **`NonOwningSpanScope`:** `close()` is no-op by design — must not be mistaken for owning scope (`NonOwningSpanScope.java:17–26`).

### Sequence pseudocode — `inSpan` Runnable

```text
inSpan(name, category, action):
  requireNonNull(action)
  try (scope = this.startSpan(name, category)):
    try:
      action.run()
    catch RuntimeException e:
      scope.recordException(e)
      throw e
  // scope.close() in finally of try-with-resources
```

---

## 5. Relation and Link Semantics

### `SpanRelation` enum

| Value | Documented semantics | `DefaultPlatformTracing` behavior |
|-------|---------------------|-----------------------------------|
| `CHILD` | Child of active context; new root if none (`SpanRelation.java:11–15`) | Default for 2-arg `startSpan`; uses current context as parent (`DefaultPlatformTracing.java:155–157`, `220–222` skips root) |
| `ROOT` | New trace, no parent (`SpanRelation.java:17–21`) | `setParent(Context.root())` (`DefaultPlatformTracing.java:220–222`) |
| `DETACHED` | No parent; links not auto-added (`SpanRelation.java:23–27`) | Same parent as ROOT; links only via `startSpanWithLinks`/`addLink` |

### Links

- **`SpanLinkContext`:** record with `traceId`, `spanId`, `traceFlags`, optional `traceState` (`SpanLinkContext.java:11–18`); factory `sampled(traceId, spanId)`.
- **`startSpanWithLinks`:** `DefaultPlatformTracing` uses `SpanRelation.CHILD` + copies links (`DefaultPlatformTracing.java:170–175`, `224–226`).
- **`addLink`:** adds to `Span.current()` if valid (`DefaultPlatformTracing.java:177–184`).

### Fallback / default interface behavior

| API | Interface default | `DefaultPlatformTracing` | `NoOpPlatformTracing` | `MeteredPlatformTracing` (injected) |
|-----|-------------------|--------------------------|----------------------|-------------------------------------|
| `startSpan(..., relation)` | Ignores relation | **Implemented** | Uses default → no-op span | Uses interface default → **relation lost** |
| `startSpanWithLinks` | Ignores links | **Implemented** | Uses default | Uses interface default → **links lost** |
| `addLink` | no-op | **Implemented** | no-op (default) | **no-op (default, not delegated)** |

**Fact:** Interface Javadoc explicitly states relation is ignored in default implementation (`PlatformTracing.java:69–70`) and links ignored (`PlatformTracing.java:108–109`).

**Fact:** Links are **fully implemented** in `DefaultPlatformTracing` but **silently ignored** when calling through interface defaults on non-overriding implementations.

### Active span / trace id

- `currentTraceId()` / `currentSpanId()` read `Span.current().getSpanContext()` if valid (`DefaultPlatformTracing.java:141–151`).
- `NoOpPlatformTracing` always returns empty (`NoOpPlatformTracing.java:22–30`).

---

## 6. Builder API Analysis

### Builder inventory

| Builder | Facade fallback | Core implementation | Category | Required semantic fields (policy) | Validation location | Notes |
|---------|-----------------|-------------------|----------|--------------------------------|---------------------|-------|
| `InternalSpanBuilder` | `FacadeInternalSpanBuilder` | `InternalSpanBuilderImpl` | `INTERNAL` | Low-cardinality name | `AttributePolicy` in core | Safe for agent-first; primary path |
| `HttpServerSpanBuilder` | `FacadeHttpServerSpanBuilder` | `HttpServerSpanBuilderImpl` | `HTTP_SERVER` | method, route (via defaults) | core `validateAndNormalize` | Escape-hatch |
| `HttpClientSpanBuilder` | `FacadeHttpClientSpanBuilder` | `HttpClientSpanBuilderImpl` | `HTTP_CLIENT` | method, url | core | URL sanitization in tests |
| `DatabaseSpanBuilder` | `FacadeDatabaseSpanBuilder` | `DatabaseSpanBuilderImpl` | `DATABASE` | system, collection, operation | core | SQL lazy + sanitized |
| `RpcServerSpanBuilder` | `FacadeRpcServerSpanBuilder` | `RpcServerSpanBuilderImpl` | `RPC_SERVER` | service, method | core | Escape-hatch |
| `RpcClientSpanBuilder` | `FacadeRpcClientSpanBuilder` | `RpcClientSpanBuilderImpl` | `RPC_CLIENT` | service, method | core | Escape-hatch |
| `KafkaProducerSpanBuilder` | `FacadeKafkaProducerSpanBuilder` | `KafkaProducerSpanBuilderImpl` | `KAFKA_PRODUCER` | destination, operation | core | Escape-hatch |
| `KafkaConsumerSpanBuilder` | `FacadeKafkaConsumerSpanBuilder` | `KafkaConsumerSpanBuilderImpl` | `KAFKA_CONSUMER` | destination, operation | core | Escape-hatch |

Facade pattern: `AbstractFacadeTypedSpanBuilder` → `tracing.startSpan(name, category)` + attribute application (`AbstractFacadeTypedSpanBuilder.java:26–97`).

Core pattern: `AbstractPlatformSpanBuilder` → OTel `Tracer` directly + anti-double guard (`AbstractPlatformSpanBuilder.java:44–144`).

### Escape-hatch vs primary path

- **Documented:** escape-hatch builders for operations **not** covered by OTel Java Agent (`PlatformTracing.java:187–192`, `docs/tracing/anti-double-instrumentation.md`).
- **`internalSpan()` / `startInternal`:** recommended for business operations (`PlatformTracing.java:164–173`).
- **`SpanEnricher`:** agent-first enrichment path (bean in `SemanticLayerAutoConfiguration.java:55–59`) — separate from `PlatformTracing` but adjacent.

### Double instrumentation guards

1. OTel Agent span suppression flag (documented).
2. Runtime anti-double guard (model B) — platform category marker in Context (`AbstractPlatformSpanBuilder.java:113–119`).
3. ArchUnit `ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION` (`TracingArchRules.java:114–119`).
4. `@SuppressAgentInstrumentation` annotation.

### API / implementation alignment

- **Aligned:** `DefaultPlatformTracing` overrides all builder factories to return `*Impl`.
- **Misaligned:** Interface defaults return `Facade*` — any third-party or `NoOpPlatformTracing` implementation lacks semconv validation and anti-double guard.
- **Metrics gap:** `MeteredPlatformTracing` documents that builder path does not increment `spans_started` (`MeteredPlatformTracing.java:57–59`).

---

## 7. Implementation and Decorator Map

| Class | Module | Role | Overrides default methods? | OpenTelemetry? | Micrometer? | Risk |
|-------|--------|------|----------------------------|----------------|-------------|------|
| `PlatformTracing` (interface) | api | Contract + defaults | N/A (defines defaults) | compileOnly in api | No | Medium |
| `DefaultPlatformTracing` | core | Production OTel facade | Overrides relation, links, builders | **Yes** (`Tracer`) | No (SemconvMetrics optional) | Low |
| `NoOpPlatformTracing` | core | Degraded / disabled mode | Minimal (2 abstract only) | No | No | Low |
| `MeteredPlatformTracing` | autoconfigure | Metrics decorator | **Partial** — only 2-arg `startSpan`, builders, `recordException` | Delegates | **Yes** (counters) | **Critical** |
| `Facade*SpanBuilder` | api | Fallback builders | N/A | No | No | Medium |
| `*SpanBuilderImpl` | core | Policy builders | N/A | **Yes** | No | Low |
| `OwningSpanScope` | core | Owning scope | N/A | **Yes** | No | Medium |
| `NonOwningSpanScope` | core | Enrich-only scope | N/A | **Yes** | No | Medium |
| `NoOpSpanScope` | core | Kill-switch scope | N/A | No | No | Low |

### Decorator default-method dispatch (detailed)

Java resolves interface default methods on the **runtime class** of `this`.

When application code holds `PlatformTracing` injected as `MeteredPlatformTracing`:

```text
startRootSpan("job", INTERNAL)
  → PlatformTracing.startRootSpan (default)
  → PlatformTracing.startSpan(name, category, ROOT) (default)
  → this.startSpan(name, category)   // THIS = MeteredPlatformTracing
  → MeteredPlatformTracing.startSpan (override)
  → delegate.startSpan(name, category)
  → DefaultPlatformTracing.startSpan → CHILD, not ROOT
```

Same for `startSpanWithLinks` and `inSpan(..., ROOT, ...)`.

`addLink` never reaches delegate — interface default no-op on `MeteredPlatformTracing`.

**Confirmed by source:** `MeteredPlatformTracing.java` ends at line 118; no overrides for relation/links/`inSpan`.

**Comment verification:** Javadoc states decorators get correct side effects because wrapped `startSpan` is called via `this`, and **forbids** overriding `inSpan` (`PlatformTracing.java:261–264`). The comment assumes relation methods are overridden or not used through defaults — currently **not true** for `MeteredPlatformTracing`.

### No other `PlatformTracing` implementations found

Grep for `implements PlatformTracing` found exactly three classes: `DefaultPlatformTracing`, `NoOpPlatformTracing`, `MeteredPlatformTracing`.

---

## 8. Auto-Configuration and Wiring

### Bean creation — `TracingCoreAutoConfiguration`

| Bean | Lines | Conditions | Implementation |
|------|-------|------------|----------------|
| `SdkModeDiagnostics` | 50–68 | `@ConditionalOnMissingBean` | Diagnostics only |
| `PlatformTracing platformTracing` | 80–126 | `@ConditionalOnClass(OpenTelemetry)`, `platform.tracing.enabled=true` (default), `@ConditionalOnMissingBean` | `NoOpPlatformTracing` if DISABLED or non-functional OTel; else `DefaultPlatformTracing` |
| `PlatformTracingJmxClient` | 164–168 | always (missing bean) | JMX client |
| `PlatformContextPropagation` | 180–189 | missing bean | NoOp if `NoOpPlatformTracing`, else OTel |

File: `platform-tracing-spring-boot-autoconfigure/.../TracingCoreAutoConfiguration.java`.

**OpenTelemetry resolution order:** user bean → `GlobalOpenTelemetry` → functional probe → NoOp (`TracingCoreAutoConfiguration.java:105–125`).

**Dependencies injected into `DefaultPlatformTracing`:** `AttributePolicy`, `ExceptionRecorder` (with secure defaults if beans absent — `TracingCoreAutoConfiguration.java:89–97`).

### Decoration chain — `TracingMetricsAutoConfiguration`

| Bean | Lines | Conditions |
|------|-------|------------|
| `PlatformTracingMetrics` | 43–47 | `@ConditionalOnClass(MeterRegistry)`, `@ConditionalOnBean(MeterRegistry)` |
| `MicrometerSemconvMetrics` | 54–58 | missing `SemconvMetrics` |
| `MeteredPlatformTracing` | 106–112 | `@Primary`, `@ConditionalOnMissingBean(MeteredPlatformTracing)` |

File: `TracingMetricsAutoConfiguration.java`.

**Ordering:** `@AutoConfigureBefore(TracingAopAutoConfiguration, ServletTracingAutoConfiguration, ReactiveTracingAutoConfiguration)` — consumers get decorated bean (`TracingMetricsAutoConfiguration.java:33–37`).

### Semantic layer — `SemanticLayerAutoConfiguration`

- `AttributePolicy`, `SpanEnricher`, `ExceptionMessagePolicy`, `ExceptionRecorder` beans (`SemanticLayerAutoConfiguration.java:40–75`).
- Wired into `DefaultPlatformTracing` via `ObjectProvider` in core auto-config.

### Property gates

- `platform.tracing.enabled` — master switch (default true).
- `platform.tracing.sdk.mode` — DISABLED → NoOp facade.
- `platform.tracing.semantic.validation-mode` — STRICT/WARN/DISABLED.

### Failure modes

- No OTel + no agent → `NoOpPlatformTracing` (safe degraded).
- `GlobalOpenTelemetry.get()` throws → NoOp + warn log (`TracingCoreAutoConfiguration.java:111–116`).
- Missing `MeterRegistry` → no `MeteredPlatformTracing`; raw `DefaultPlatformTracing` or NoOp injected.

### Module ownership

| Module | Owns |
|--------|------|
| `platform-tracing-api` | `PlatformTracing`, `SpanScope`, `SpanRelation`, `SpanLinkContext`, builder interfaces, `Facade*` |
| `platform-tracing-core` | `DefaultPlatformTracing`, `*Impl` builders, scopes, `ExceptionRecorder` |
| `platform-tracing-spring-boot-autoconfigure` | Bean wiring, `MeteredPlatformTracing`, properties |
| Starters (`platform-tracing-spring-boot-starter-servlet/reactive`) | Aggregate dependencies (per module taxonomy doc) |

API dependency direction: api → slf4j + jakarta.annotation; OTel compileOnly (`platform-tracing-api/build.gradle`).

---

## 9. Tests and Verification Coverage

### Test inventory

| Test class | Module | Behavior covered | Strength | Missing assertions |
|------------|--------|------------------|----------|------------------|
| `DefaultPlatformTracingTest` | core | CHILD parent trace, ROOT new trace, DETACHED no parent, links, addLink, facade kill-switch | **Strong** (InMemorySpanExporter) | No Micrometer decorator |
| `DefaultPlatformTracingInSpanTest` | core | inSpan create/close, RuntimeException, checked Exception, nesting, ROOT relation, LIFO restore | **Strong** | Uses raw `DefaultPlatformTracing`, not Spring bean |
| `EscapeHatchSpanBuilderTest` | core | HTTP/DB/RPC/Kafka builders, kind, naming, sanitization, STRICT violations | **Strong** | No agent double-instrumentation e2e |
| `InternalSpanBuilderImplTest` | core | Internal builder specifics | Medium | — |
| `SpanEnricherTest` | core | Enrich path (adjacent) | Medium | — |
| `TracingAutoConfigurationTest` | autoconfigure | NoOp vs Default bean, disable property, MeteredPlatformTracing @Primary | **Strong** for wiring | No relation/links through decorator |
| `PlatformTracingMetricsTest` | autoconfigure | Counter increments on metrics class | Medium | Does not assert decorator + span creation integration |
| `TracedAspectTest` | autoconfigure | `@Traced` AOP uses `PlatformTracing` | Medium | — |
| `PlatformTracingTestExtensionTest` | test | JUnit extension injects `DefaultPlatformTracing` | Medium | — |
| `TracingArchRules` (consumers) | test | Escape-hatch ArchUnit, @Traced+@Observed | Defense-in-depth | try-with-resources rule explicitly **not** implemented |
| `ExceptionEventScrubbingE2ETest` | e2e | inSpan + exception scrubbing | E2E | — |
| JMH benches (`StartSpanBenchmark`, `TypedBuilderBenchmark`, etc.) | bench | Performance baselines | Perf only | Not behavioral |

### Behavior protected by tests (summary)

- ✅ Child/root/detached span relations on `DefaultPlatformTracing`
- ✅ Links creation and `addLink`
- ✅ `inSpan` exception recording and nesting
- ✅ Builder semconv (core impl)
- ✅ Spring bean selection and Metered wrapper presence
- ✅ Facade kill-switch

### Weak / missing coverage

- ❌ `MeteredPlatformTracing` + `SpanRelation.ROOT` / links / `addLink`
- ❌ `NoOpPlatformTracing` + relation convenience methods (low risk — all no-op)
- ❌ Facade fallback builders under custom `PlatformTracing` impl
- ❌ Reactive misuse detection (documented only)
- ❌ try-with-resources enforcement (ArchUnit backlog)

### Verification commands

**Not run during this investigation** (source-only). Recommended before refactoring:

```powershell
Set-Location E:\Platform_Traces
.\gradlew.bat :platform-tracing-core:test --tests "space.br1440.platform.tracing.core.DefaultPlatformTracing*"
.\gradlew.bat :platform-tracing-core:test --tests "space.br1440.platform.tracing.core.span.EscapeHatchSpanBuilderTest"
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "space.br1440.platform.tracing.autoconfigure.TracingAutoConfigurationTest"
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "space.br1440.platform.tracing.autoconfigure.aspect.TracedAspectTest"
```

Suggested **new** tests (not present):

```powershell
# After adding MeteredPlatformTracing relation/links tests:
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --tests "*MeteredPlatformTracing*"
```

---

## 10. Documentation and ADR Alignment

| Document | Claim | Code evidence | Status |
|----------|-------|---------------|--------|
| `PlatformTracing.java` Javadoc | Default `startSpan(..., relation)` ignores relation | Default body delegates to 2-arg; `DefaultPlatformTracing` overrides | **Partially confirmed** (true for default; false for production impl when not decorated) |
| `PlatformTracing.java` Javadoc | Default `startSpanWithLinks` ignores links | Default → 2-arg; `DefaultPlatformTracing` overrides | **Partially confirmed** |
| `PlatformTracing.java` Javadoc | Do not override `inSpan` in decorators | `MeteredPlatformTracing` complies | **Confirmed** |
| `PlatformTracing.java` Javadoc | No `inSpan(Mono/Flux)` | No such methods on interface | **Confirmed** |
| `ADR-reactor-no-inspan-v0.1.0.md` | Reactive assembly must not use sync SpanScope | No reactive API on facade | **Confirmed** |
| `docs/tracing/links-kafka.md` | `startSpanWithLinks`, `addLink`, `startRootSpan` for batch Kafka | Implemented in `DefaultPlatformTracing` | **Partially confirmed** — broken through `MeteredPlatformTracing` defaults |
| `docs/tracing/anti-double-instrumentation.md` | Escape-hatch + 3 guard lines | ArchUnit + `AbstractPlatformSpanBuilder` guard | **Confirmed** |
| `ADR-typed-span-api-semantic-layer.md` | Internal builder safe; escape-hatch guarded | `InternalSpanBuilderImpl`, `AttributePolicy` | **Confirmed** |
| `platform-tracing-module-taxonomy.md` | api = public contract; core = OTel-coupled impl | Gradle deps | **Confirmed** |
| `MeteredPlatformTracing` Javadoc | Decorator delegates; inSpan metrics via `this` | Relation/links not delegated | **Contradicted** for relation/link APIs |

---

## 11. Refactoring Risk Register

| ID | Risk | Area | Severity | Evidence | Mitigation before refactoring |
|----|------|------|----------|----------|-------------------------------|
| R-01 | `MeteredPlatformTracing` breaks `SpanRelation` and links via interface defaults | Decorator | **Critical** | §7; `MeteredPlatformTracing.java:44–51` vs `PlatformTracing.java:73–77,110–122` | Add failing test; override/delegate 3-arg `startSpan`, `startSpanWithLinks`, `addLink` |
| R-02 | Facade fallback silently drops links and semconv validation | API defaults | **High** | `PlatformTracing.java:108–115`; `AbstractFacadeTypedSpanBuilder.java:91–97` | Document; consider making relation methods abstract |
| R-03 | Public default-method behavior change affects all partial implementors | API evolution | **High** | `NoOpPlatformTracing`, `MeteredPlatformTracing` | Characterization tests per implementation |
| R-04 | Unclosed `SpanScope` → ThreadLocal leak | Runtime | **High** | Javadoc `PlatformTracing.java:56–57` | PMD/SpotBugs rule (ArchUnit explicitly deferred) |
| R-05 | Reactive sync wrapper misuse | Runtime | **High** | `PlatformTracing.java:266–268`; ADR-reactor | Lint/docs; no API until v1.1 |
| R-06 | Escape-hatch double instrumentation with Agent | Runtime | **High** | anti-double doc; `AbstractPlatformSpanBuilder.java:113–119` | Keep ArchUnit in consumer services |
| R-07 | `inSpan` override in decorator → double `spans_started` | Metrics | **High** | `PlatformTracing.java:261–264` | ArchUnit rule on decorators |
| R-08 | Builder path skips `spans_started` metric | Observability | **Medium** | `MeteredPlatformTracing.java:57–59` | Accept or add builder hooks |
| R-09 | Checked exception overload only on `ThrowingSupplier` | API ergonomics | **Medium** | `PlatformTracing.java:308–319` | Document; avoid adding conflicting overloads |
| R-10 | `NonOwningSpanScope.close()` no-op — caller may think span ended | Lifecycle | **Medium** | `NonOwningSpanScope.java:17–26` | Tests + Javadoc in consumer guides |
| R-11 | Kill-switch mid-flight — open scopes unaffected | Runtime | **Medium** | `DefaultPlatformTracing.java:198–204` | Document operational behavior |
| R-12 | Spring `@Primary` hides raw `DefaultPlatformTracing` bean | Wiring | **Low** | `TracingMetricsAutoConfiguration.java:106–112` | `@Qualifier` if internal access needed |
| R-13 | Semconv validation mode STRICT in prod | Config | **Medium** | `SemanticLayerAutoConfiguration.java:78–81` | Startup WARN already present |

---

## 12. Missing Information

| Item | Category |
|------|----------|
| Production usage frequency of `startRootSpan` / links APIs | Requires runtime verification |
| Whether any service observed broken ROOT traces with Micrometer enabled | Requires runtime verification |
| Full e2e no-duplicate-span coverage with Agent + escape-hatch | Missing test (partial ArchUnit only) |
| `PlatformTracingMetricsBridge` for scope-closed metrics from core | Missing documentation / implementation (noted as backlog in `PlatformTracingMetrics.java:19–22`) |
| Micrometer Observation integration details beyond suppress predicates | Missing documentation in facade code (lives in web autoconfigure modules) |
| Binary compatibility policy for adding new default methods | Requires architecture decision |
| Performance baseline for facade hot path post-refactor | Requires runtime verification (JMH exists but not run here) |

---

## 13. Suggested Perplexity Deep Research Questions

1. **Decorator + interface default methods:** When a Java decorator implements an interface with default methods that call `this.startSpan(...)`, which overrides are required to preserve semantics of other default methods (`startRootSpan`, `startSpanWithLinks`)? *Relevant:* `PlatformTracing.java`, `MeteredPlatformTracing.java`. *Expected:* Java language spec + best practices for decorator correctness. *Basis:* Java API design.

2. **Should relation-aware span methods be abstract rather than default** in a tracing facade where multiple implementations exist (production, no-op, metrics decorator)? *Relevant:* `PlatformTracing`, `DefaultPlatformTracing`, `MeteredPlatformTracing`. *Expected:* trade-off analysis (API stability vs correctness). *Basis:* Java interface evolution best practices.

3. **OpenTelemetry span links for Kafka batch consumers:** Is one CONSUMER span with N links the recommended pattern vs DETACHED + links vs ROOT + links? *Relevant:* `docs/tracing/links-kafka.md`, `DefaultPlatformTracing.startSpanWithLinks`. *Expected:* OTel semconv + Java instrumentation precedent. *Basis:* OpenTelemetry docs.

4. **Span parent `Context.root()` vs explicit new trace** for scheduled jobs — any sampler/propagation differences? *Relevant:* `DefaultPlatformTracing.java:220–222`, `SpanRelation.ROOT`. *Expected:* OTel Context behavior. *Basis:* OpenTelemetry docs.

5. **Anti-double instrumentation:** How does `otel.instrumentation.experimental.span-suppression-strategy=semconv` interact with manually created CLIENT/SERVER spans from application classloader? *Relevant:* `docs/tracing/anti-double-instrumentation.md`. *Expected:* Agent behavior limits. *Basis:* OTel Java Agent docs.

6. **Micrometer Observation vs manual OTel spans:** When should platform code use `@Observed` vs `PlatformTracing.inSpan` vs Agent-only? *Relevant:* `TracedAspect`, `TracingArchRules.NO_TRACED_AND_OBSERVED`. *Expected:* integration patterns. *Basis:* Micrometer Observation docs.

7. **Exception recording:** Should tracing facades use `Span.recordException` or custom sanitized events (this codebase uses `ExceptionRecorder`)? *Relevant:* `ExceptionRecorder.java`, scrubbing ADRs. *Expected:* security + OTel event model guidance. *Basis:* OTel + security best practices.

8. **Builder API design for semconv compliance:** Fluent builders with `validateAndNormalize` before `startSpan()` vs post-start attribute setting — impact on head sampling? *Relevant:* `AbstractPlatformSpanBuilder.java:122–131`. *Expected:* sampling attribute visibility rules. *Basis:* OpenTelemetry SDK docs.

9. **Idempotent `SpanScope.close()`:** Industry patterns for detecting double-close vs silent no-op? *Relevant:* `OwningSpanScope.java:109–125`, `PlatformTracingMetrics.SCOPE_DOUBLE_CLOSE_TOTAL`. *Expected:* metrics/alerts guidance. *Basis:* tracing facade best practices.

10. **Reactive tracing without `inSpan(Mono)`:** Recommended patterns using `Mono.using` + OTel Context propagation hooks in Spring Boot 3.5 / Reactor 3.5? *Relevant:* `ADR-reactor-no-inspan-v0.1.0.md`. *Expected:* reference implementation sketch. *Basis:* Reactor + Micrometer Context Propagation docs.

11. **Link limits:** Default OTel SDK link count limits and batch consumer N-message scenarios? *Relevant:* `SpanLimitsVerificationTest` (otel-extension). *Expected:* limits + truncation behavior. *Basis:* OpenTelemetry SDK docs.

12. **Spring `@Primary` decorator bean:** Is wrapping core beans with `@Primary` decorators an anti-pattern for interfaces with default methods? *Relevant:* `TracingMetricsAutoConfiguration.java:106–112`. *Expected:* Spring Boot wiring alternatives (BeanPostProcessor, ObservationRegistry). *Basis:* Spring Boot auto-configuration practices.

13. **Testing strategy for facade refactoring:** Characterization tests via InMemorySpanExporter vs OpenTelemetry `@WithSpan` — minimum viable gate? *Relevant:* `DefaultPlatformTracingTest`, `PlatformTracingTestExtension`. *Expected:* test pyramid recommendation. *Basis:* OTel testing docs.

14. **ContextKey category marker for anti-double guard:** Is storing `SpanCategory` in OTel Context considered stable public API or internal implementation detail? *Relevant:* `AbstractPlatformSpanBuilder.java:115`, `PlatformSpanContextKeys`. *Expected:* encapsulation guidance. *Basis:* OTel Context API design.

15. **Kill-switch facade without disabling Agent:** Operational patterns for `setFacadeEnabled(false)` during incidents? *Relevant:* `DefaultPlatformTracing.java:198–204`. *Expected:* SRE runbook patterns. *Basis:* platform engineering best practices.

16. **ThrowingSupplier vs Callable in public API:** Pros/cons for checked exception propagation in Java 17+ tracing facades? *Relevant:* `ThrowingSupplier.java`, `inSpan` overloads. *Expected:* API ergonomics comparison. *Basis:* Java API design.

17. **Span link traceState parsing:** Correctness of manual `traceState` comma-split parsing vs W3C tracestate spec? *Relevant:* `DefaultPlatformTracing.resolveTraceState` (`DefaultPlatformTracing.java:242–256`). *Expected:* spec compliance review. *Basis:* W3C trace-context spec.

18. **Future `PlatformTracing` v2:** Should typed builders return a separate `SpanBuilder` type decoupled from `PlatformTracing` to simplify decoration? *Relevant:* entire builder map §6. *Expected:* architectural options. *Basis:* facade best practices + OpenTelemetry patterns.

---

## 14. Appendix A — Exact File/Class Inventory

| File | Class/interface | Why relevant |
|------|-----------------|--------------|
| `platform-tracing-api/.../PlatformTracing.java` | `PlatformTracing` | Primary investigation target |
| `platform-tracing-api/.../span/SpanScope.java` | `SpanScope` | Lifecycle handle |
| `platform-tracing-api/.../span/SpanRelation.java` | `SpanRelation` | Parent/root/detached semantics |
| `platform-tracing-api/.../span/SpanLinkContext.java` | `SpanLinkContext` | Link DTO |
| `platform-tracing-api/.../span/SpanCategory.java` | `SpanCategory` | Category enum |
| `platform-tracing-api/.../span/SpanResult.java` | `SpanResult` | Result status on scope |
| `platform-tracing-api/.../util/ThrowingSupplier.java` | `ThrowingSupplier` | inSpan checked exceptions |
| `platform-tracing-api/.../span/builder/PlatformSpanBuilder.java` | `PlatformSpanBuilder` | Builder base contract |
| `platform-tracing-api/.../span/builder/InternalSpanBuilder.java` | `InternalSpanBuilder` | Internal builder |
| `platform-tracing-api/.../span/builder/HttpServerSpanBuilder.java` | `HttpServerSpanBuilder` | HTTP SERVER escape-hatch |
| `platform-tracing-api/.../span/builder/HttpClientSpanBuilder.java` | `HttpClientSpanBuilder` | HTTP CLIENT escape-hatch |
| `platform-tracing-api/.../span/builder/DatabaseSpanBuilder.java` | `DatabaseSpanBuilder` | DB escape-hatch |
| `platform-tracing-api/.../span/builder/RpcServerSpanBuilder.java` | `RpcServerSpanBuilder` | RPC SERVER escape-hatch |
| `platform-tracing-api/.../span/builder/RpcClientSpanBuilder.java` | `RpcClientSpanBuilder` | RPC CLIENT escape-hatch |
| `platform-tracing-api/.../span/builder/KafkaProducerSpanBuilder.java` | `KafkaProducerSpanBuilder` | Kafka producer escape-hatch |
| `platform-tracing-api/.../span/builder/KafkaConsumerSpanBuilder.java` | `KafkaConsumerSpanBuilder` | Kafka consumer escape-hatch |
| `platform-tracing-api/.../span/builder/AbstractFacadeTypedSpanBuilder.java` | `AbstractFacadeTypedSpanBuilder` | Shared facade builder logic |
| `platform-tracing-api/.../span/builder/FacadeInternalSpanBuilder.java` | `FacadeInternalSpanBuilder` | Internal facade fallback |
| `platform-tracing-api/.../span/builder/FacadeHttpServerSpanBuilder.java` | `FacadeHttpServerSpanBuilder` | HTTP SERVER facade |
| `platform-tracing-api/.../span/builder/FacadeHttpClientSpanBuilder.java` | `FacadeHttpClientSpanBuilder` | HTTP CLIENT facade |
| `platform-tracing-api/.../span/builder/FacadeDatabaseSpanBuilder.java` | `FacadeDatabaseSpanBuilder` | DB facade |
| `platform-tracing-api/.../span/builder/FacadeRpcServerSpanBuilder.java` | `FacadeRpcServerSpanBuilder` | RPC SERVER facade |
| `platform-tracing-api/.../span/builder/FacadeRpcClientSpanBuilder.java` | `FacadeRpcClientSpanBuilder` | RPC CLIENT facade |
| `platform-tracing-api/.../span/builder/FacadeKafkaProducerSpanBuilder.java` | `FacadeKafkaProducerSpanBuilder` | Kafka producer facade |
| `platform-tracing-api/.../span/builder/FacadeKafkaConsumerSpanBuilder.java` | `FacadeKafkaConsumerSpanBuilder` | Kafka consumer facade |
| `platform-tracing-core/.../DefaultPlatformTracing.java` | `DefaultPlatformTracing` | Primary OTel implementation |
| `platform-tracing-core/.../NoOpPlatformTracing.java` | `NoOpPlatformTracing` | No-op implementation |
| `platform-tracing-core/.../span/OwningSpanScope.java` | `OwningSpanScope` | Owning scope |
| `platform-tracing-core/.../span/NonOwningSpanScope.java` | `NonOwningSpanScope` | Enrich-only scope |
| `platform-tracing-core/.../span/NoOpSpanScope.java` | `NoOpSpanScope` | Kill-switch scope |
| `platform-tracing-core/.../span/AbstractPlatformSpanBuilder.java` | `AbstractPlatformSpanBuilder` | Policy builder base |
| `platform-tracing-core/.../span/InternalSpanBuilderImpl.java` | `InternalSpanBuilderImpl` | Internal policy builder |
| `platform-tracing-core/.../span/HttpServerSpanBuilderImpl.java` | `HttpServerSpanBuilderImpl` | HTTP SERVER impl |
| `platform-tracing-core/.../span/HttpClientSpanBuilderImpl.java` | `HttpClientSpanBuilderImpl` | HTTP CLIENT impl |
| `platform-tracing-core/.../span/DatabaseSpanBuilderImpl.java` | `DatabaseSpanBuilderImpl` | DB impl |
| `platform-tracing-core/.../span/RpcServerSpanBuilderImpl.java` | `RpcServerSpanBuilderImpl` | RPC SERVER impl |
| `platform-tracing-core/.../span/RpcClientSpanBuilderImpl.java` | `RpcClientSpanBuilderImpl` | RPC CLIENT impl |
| `platform-tracing-core/.../span/KafkaProducerSpanBuilderImpl.java` | `KafkaProducerSpanBuilderImpl` | Kafka producer impl |
| `platform-tracing-core/.../span/KafkaConsumerSpanBuilderImpl.java` | `KafkaConsumerSpanBuilderImpl` | Kafka consumer impl |
| `platform-tracing-core/.../span/SpanKinds.java` | `SpanKinds` | Category → OTel SpanKind |
| `platform-tracing-core/.../exception/ExceptionRecorder.java` | `ExceptionRecorder` | Sanitized exception events |
| `platform-tracing-core/.../semconv/AttributePolicy.java` | `AttributePolicy` | Semconv validation |
| `platform-tracing-core/.../span/SpanEnricher.java` | `SpanEnricher` | Agent span enrichment |
| `platform-tracing-spring-boot-autoconfigure/.../TracingCoreAutoConfiguration.java` | `TracingCoreAutoConfiguration` | Bean creation |
| `platform-tracing-spring-boot-autoconfigure/.../TracingMetricsAutoConfiguration.java` | `TracingMetricsAutoConfiguration` | Metered decorator |
| `platform-tracing-spring-boot-autoconfigure/.../SemanticLayerAutoConfiguration.java` | `SemanticLayerAutoConfiguration` | Policy/recorder beans |
| `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredPlatformTracing.java` | `MeteredPlatformTracing` | Metrics decorator |
| `platform-tracing-spring-boot-autoconfigure/.../metrics/PlatformTracingMetrics.java` | `PlatformTracingMetrics` | Metric names/API |
| `platform-tracing-spring-boot-autoconfigure/.../aspect/TracedAspect.java` | `TracedAspect` | @Traced AOP consumer |
| `platform-tracing-test/.../PlatformTracingTestExtension.java` | `PlatformTracingTestExtension` | JUnit 5 test support |
| `platform-tracing-test/.../arch/TracingArchRules.java` | `TracingArchRules` | ArchUnit rules |
| `platform-tracing-core/src/test/.../DefaultPlatformTracingTest.java` | test | Relation/links coverage |
| `platform-tracing-core/src/test/.../DefaultPlatformTracingInSpanTest.java` | test | inSpan coverage |
| `platform-tracing-core/src/test/.../EscapeHatchSpanBuilderTest.java` | test | Builder coverage |
| `platform-tracing-spring-boot-autoconfigure/src/test/.../TracingAutoConfigurationTest.java` | test | Spring wiring |
| `docs/tracing/anti-double-instrumentation.md` | doc | Double instrumentation guide |
| `docs/tracing/links-kafka.md` | doc | Links usage guide |
| `docs/decisions/ADR-reactor-no-inspan-v0.1.0.md` | ADR | Reactive boundary |
| `docs/decisions/ADR-typed-span-api-semantic-layer.md` | ADR | Builder architecture |

---

## 15. Appendix B — Commands Used

**No shell commands were executed during this investigation.**

Analysis was performed exclusively via read-only source inspection (file reads and workspace grep/glob searches). No build outputs were modified. No Gradle test runs were executed — test coverage statements are based on test source content, not on executed results.

If verification is needed, run the commands listed in §9 and record outcomes separately.
