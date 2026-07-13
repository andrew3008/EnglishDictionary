# ADR — TraceOperations Micrometer Observation Boundary

| Поле | Значение |
|------|----------|
| Статус | **Accepted** |
| Дата | 2026-07-06 |
| Контекст | Mandatory gate before Slice 1A ([platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) v3.4.2) |
| Связанные ADR | [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md), [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md), [ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md) |
| Стек (repo) | Spring Boot **3.5.5**, OTel instrumentation BOM **2.28.1**, OTel SDK via Agent / bridge-otel (см. `gradle.properties`, ADR-otel-direct-integration) |

> **Accepted. This ADR unblocks the Micrometer Observation API Boundary gate for Slice 1A. Slice 1A is limited to additive API skeleton and architecture tests only.**

## Acceptance note

- Status: Accepted
- Accepted option: Option C — Hybrid model
- Approval source: Architect approval communicated for v3.4.2 Slice 1A gate before implementation.
- Approval date: 2026-07-06
- Scope of acceptance: Micrometer Observation API Boundary and Slice 1A gate decisions
- Slice impact: unblocks Slice 1A only; does not authorize Slice 1B, Slice 2, or R01 production fix

---

## Context

TraceOperations v3.4.2 redesign introduces an internal `TracingImplementation` boundary, a narrow public facade (`traceContext()` + `manual()`), and `MeteredTracingImplementation` for platform self-metrics. The repository already runs **three parallel telemetry paths**:

1. **OTel Java Agent** — bytecode HTTP/DB/RPC/Kafka spans (agent-first, [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)).
2. **Spring / Micrometer Observation** — framework HTTP observations, `@Observed`, conventions ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)).
3. **TraceOperations manual API** — `DefaultTraceOperations` over OpenTelemetry API `Tracer` / `SpanBuilder` ([DefaultTraceOperations.java](../../platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/DefaultTraceOperations.java)).

Without an explicit boundary decision, manual platform spans and Spring auto-observation can produce **duplicate or unsynchronized root spans** — structurally the same failure class as **R01** (two paths that silently diverge; see [R01.md](../known-issues/R01.md)).

This ADR decides ownership per path. It does **not** implement code.

---

## Problem

The refactoring plan requires answering before Slice 1A:

1. Which component owns trace lifecycle for **manual platform spans**?
2. Which component owns trace lifecycle for **Spring auto-instrumented spans**?
3. How are **duplicate spans** prevented?
4. How does the Micrometer tracing bridge coexist with **`MeteredTracingImplementation`**?
5. Should any Spring tracing handlers be **disabled, customized, or left untouched**?
6. Which **tests** prove coexistence?

Risk **R24** in the plan: *Micrometer Observation and TraceOperations create duplicate or inconsistent spans* (High, Slices 1A/7).

---

## Repository facts

Classification: **FACT** = directly observed in repository; **ASSUMPTION** = inferred; **OPEN QUESTION** = not resolved from repo alone.

| # | Finding | Class |
|---|---------|-------|
| 1 | **OpenTelemetry API/SDK:** `DefaultTraceOperations` creates spans via `OpenTelemetry.getTracer(...).spanBuilder(...)`, `Context`, `Span` ([DefaultTraceOperations.java](../../platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/DefaultTraceOperations.java)). Typed semantic builders in core use `Tracer`/`SpanBuilder` ([ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md)). SPI extension in `platform-tracing-otel-extension`. | FACT |
| 2 | **MeterRegistry:** `TracingMetricsAutoConfiguration` registers `PlatformTracingMetrics` and `@Primary` `MeteredPlatformTracing` when `MeterRegistry` bean exists ([TracingMetricsAutoConfiguration.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingMetricsAutoConfiguration.java)). | FACT |
| 3 | **ObservationRegistry / Observation API:** `io.micrometer:micrometer-observation` is an **api** dependency in autoconfigure, webmvc, webflux. Production code registers `Platform*ObservationConvention` beans and `ObservationRegistryCustomizer` suppressors ([ServletTracingAutoConfiguration.java](../../platform-tracing-autoconfigure-webmvc/src/main/java/space/br1440/platform/tracing/autoconfigure/servlet/ServletTracingAutoConfiguration.java), [WebMvcSuppressMicrometerTracingAutoConfiguration.java](../../platform-tracing-autoconfigure-webmvc/src/main/java/space/br1440/platform/tracing/autoconfigure/servlet/WebMvcSuppressMicrometerTracingAutoConfiguration.java)). `TracingObservationAutoConfiguration` registers startup diagnostics only, not span creation ([TracingObservationAutoConfiguration.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingObservationAutoConfiguration.java)). | FACT |
| 4 | **`io.micrometer.tracing`:** `compileOnly` in autoconfigure; **no production usage in core**. Test-only smoke: `MicrometerTracingMdcBridgeSmokeTest` registers `Tracer`, `TracingObservationHandler`, `ObservationRegistry` when Spring Actuator tracing autoconfig + bridge-otel are on classpath ([MicrometerTracingMdcBridgeSmokeTest.java](../../platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/mdc/MicrometerTracingMdcBridgeSmokeTest.java)). | FACT |
| 5 | **MeteredPlatformTracing:** Decorates delegate; overrides only `startSpan(name, category)`, builder factories, `currentTraceId`/`currentSpanId`, `recordException`. Does **not** override relation/links/`inSpan` — R01 ([MeteredPlatformTracing.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/metrics/MeteredPlatformTracing.java), [R01.md](../known-issues/R01.md)). | FACT |
| 6 | **Spring bean wiring:** `TracingCoreAutoConfiguration` exposes `TraceOperations` (`DefaultTraceOperations` or `NoopTraceOperations`). `TracingMetricsAutoConfiguration` wraps it in `@Primary` `MeteredPlatformTracing` when Micrometer present. | FACT |
| 7 | **Existing coexistence / duplicate tests:** `DuplicateSpansRegressionMatrixTest` (webmvc + webflux), `SuppressMicrometerTracingMetricsTest`, `TracingObservationSuppressStartupTest`, e2e `DuplicateHttpSpanAgentSmokeTest`. **No `ObservationCoexistenceTest` class exists yet** (planned Slice 7). | FACT |
| 8 | **Web / aspect auto-instrumentation:** `ServletTracingAutoConfiguration`, `ReactiveTracingAutoConfiguration`, platform Observation conventions, `@Traced` via `TracedAspect`, ArchUnit rule `NO_TRACED_AND_OBSERVED_ON_SAME_METHOD` ([TracingArchRules.java](../../platform-tracing-test/src/main/java/space/br1440/platform/tracing/test/arch/TracingArchRules.java)). | FACT |
| 9 | **Actuator diagnostics:** `TracingActuatorEndpoint` exposes `implementation` class name, SDK mode, agent detection ([TracingActuatorEndpoint.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/actuator/TracingActuatorEndpoint.java)). Plan v3.4.2 adds future `TracingDiagnostics` → `TracingDiagnosticsView` mapped DTO (not yet implemented). | FACT |
| 10 | **R01 evidence:** Slice 0B RED tests + [R01.md](../known-issues/R01.md); `knownDefectTest` tasks in core/autoconfigure Gradle. | FACT |
| 11 | **Accepted prior decision:** Typed span builders MUST NOT use Micrometer Observation ([ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md) rejects Observation for builders). | FACT |
| 12 | **Accepted prior decision:** HTTP duplicate spans with Agent mitigated by opt-in `platform.tracing.suppression.suppress-micrometer-tracing` ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)). | FACT |
| 13 | **ASSUMPTION:** Post-refactor `DefaultTracingImplementation` will continue direct OTel `Tracer` usage consistent with ADR-otel-direct-integration and typed-span ADR. | ASSUMPTION |
| 14 | **OPEN QUESTION:** Exact production rollout matrix for `suppress-micrometer-tracing` vs bridge-otel dev path vs Agent-only — documented in MIGRATION.md but fleet-specific defaults are operational, not code. | OPEN QUESTION |

---

## Decision drivers

1. Preserve **OTel topology semantics** (ROOT, DETACHED, CHILD, pre-start **links**) through `SpanSpec` governance (plan v3.4.2).
2. Prevent **silent semantic loss** via partial decorators (R01 lesson).
3. Maintain **agent-first** production model ([ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)).
4. Avoid **double instrumentation** on HTTP and business methods (existing duplicate-span suite + ArchUnit).
5. Keep **one creation boundary** for manual platform spans (`TracingImplementation.startSpan(SpanSpec)`).
6. Separate **platform self-metrics** (`MeteredTracingImplementation`) from **Spring Observation lifecycle**.
7. **Testability** with falsifiable gates (plan named test suites).

---

## Options considered

### Option A — TraceOperations manual spans use direct OpenTelemetry SDK; Spring auto-observation remains separate

**Description:** Manual platform tracing stays on OTel API/SDK (`TracingImplementation` → `Tracer.spanBuilder`). Spring/Micrometer Observation continues independently for framework observations. Coexistence enforced by configuration (suppress), conventions, and tests.

| Aspect | Assessment |
|--------|------------|
| **Pros** | Aligns with ADR-otel-direct-integration and typed-span ADR; full OTel links/ROOT/DETACHED; `SpanSpec` maps cleanly to `SpanBuilder`; matches current `DefaultTraceOperations` implementation. |
| **Cons** | Two lifecycle owners remain; requires strict duplicate-prevention rules and discipline; easy to mis-wire if future code bypasses ADR. |
| **R01 impact** | Fixes R01 by moving metering to `MeteredTracingImplementation` on full abstract SPI — not by patching facade decorator. |
| **Duplicate span risk** | Medium without suppress + ArchUnit + coexistence tests; **mitigated** by existing suppress ADR and planned `ObservationCoexistenceTest`. |
| **OTel links** | Native via `SpanBuilder.addLink` (current `DefaultTraceOperations` behavior). |
| **SpanSpec governance** | Strong — direct mapping at creation boundary. |
| **Spring auto-observation** | Unchanged; platform customizes conventions only. |
| **MeteredTracingImplementation** | Natural: decorate OTel-backed implementation, count at SPI boundary. |
| **Testability** | High — InMemorySpanExporter pattern already used in core/autoconfigure tests. |

### Option B — TraceOperations implemented as a bridge over Micrometer Observation API

**Description:** All manual platform spans created via `ObservationRegistry` / `Observation.start()` / Micrometer Tracing handlers.

| Aspect | Assessment |
|--------|------------|
| **Pros** | Single Micrometer-centric API for app code; aligns with Spring Boot 3 observation idioms (`@Observed`). |
| **Cons** | **Contradicts** accepted typed-span ADR ("Micrometer Observation for builders — rejected"); Observation lifecycle less expressive for DETACHED/ROOT/links; weakens planned `TracingImplementation.startSpan(SpanSpec)` as single OTel topology boundary; bridge-otel is compileOnly/opt-in, not production default. |
| **R01 impact** | Does not address root cause (partial delegation); may recreate similar defects via Observation handler partial overrides. |
| **Duplicate span risk** | **High** — Observation handlers already create spans for HTTP; manual Observation spans risk stacking with Agent + platform paths. |
| **OTel links** | **Uncertain** — Micrometer Tracing link support not evidenced in platform production code; would require mapping layer not present in repo. |
| **SpanSpec governance** | **Weak** — Observation conventions ≠ platform semconv builder validation model. |
| **Spring auto-observation** | Collapses distinction between framework and platform manual paths. |
| **MeteredTracingImplementation** | Ambiguous — metrics vs Observation handlers overlap. |
| **Testability** | Medium — bridge path only covered by smoke test, not topology matrix. |

### Option C — Hybrid: Spring/Micrometer owns auto-observation; platform manual tracing governed by TracingImplementation (direct OTel)

**Description:** **Separate intentional telemetry paths** with explicit ownership:

- **Auto path:** OTel Agent (production spans) + Spring Micrometer Observation (framework observations, conventions, optional bridge-otel dev/staging) — platform does **not** re-wrap every Observation into `TraceOperations`.
- **Manual path:** `traceOperations.manual()` → `TracingImplementation.startSpan(SpanSpec)` → OTel SDK — platform-owned topology, governance, links.
- **Metrics path:** `MeteredTracingImplementation` decorates `TracingImplementation` only — never public facade, never Observation handlers.

| Aspect | Assessment |
|--------|------------|
| **Pros** | Matches repository reality and accepted ADRs; preserves OTel semantics; clarifies ownership; extends existing suppress/convention/ArchUnit patterns; directly supports v3.4.2 invariants. |
| **Cons** | Requires developer education ("when to use manual() vs rely on Agent/Observation"); more moving parts documented in ADR + tests. |
| **R01 impact** | Structural fix at SPI; hybrid keeps Observation out of manual creation boundary. |
| **Duplicate span risk** | **Lowest** when combined with suppress policy, `@Traced`/`@Observed` mutual exclusion, and `ObservationCoexistenceTest`. |
| **OTel links** | Full via OTel at `TracingImplementation` layer. |
| **SpanSpec governance** | Strong at single creation boundary. |
| **Spring auto-observation** | Preserved and customized (conventions), not replaced. |
| **MeteredTracingImplementation** | Clear scope — SPI decorator only. |
| **Testability** | Highest — separates concerns; plan named suites map cleanly. |

---

## Scoring (1 = poor, 5 = excellent)

| Criterion | A — Direct OTel | B — Observation bridge | C — Hybrid |
|-----------|:--:|:--:|:--:|
| Preserves OTel topology/link semantics | 5 | 2 | 5 |
| Minimizes duplicate-span risk | 3 | 2 | 5 |
| Fits SpanSpec governance | 5 | 2 | 5 |
| Keeps TracingImplementation as single creation boundary | 5 | 2 | 5 |
| Works with Spring Boot auto-observation | 4 | 4 | 5 |
| Testability | 4 | 3 | 5 |
| Implementation complexity | 4 | 3 | 3 |
| Long-term maintainability | 4 | 2 | 5 |
| **Total** | **34** | **20** | **38** |

---

## Decision

**Recommend Option C — Hybrid model.**

Option A is a subset of Option C (direct OTel manual path) but **without explicit ownership rules** for Spring auto-observation and metering. Option B is **rejected** because it conflicts with accepted ADRs, lacks repository evidence for links/topology mapping, and increases duplicate-span risk.

---

## Required decision answers

| Question | Decision |
|----------|----------|
| **1. Manual platform span lifecycle owner** | **`TracingImplementation`** (concrete: `DefaultTracingImplementation` or successor) using OpenTelemetry API `Tracer` / `SpanBuilder` at a **single** `TracingImplementation.startSpan(SpanSpec)` boundary. Public `traceOperations.manual()` (`manual().operation(...)`, `manual().transport()...`, `manual().spanFromSpec(spec)`) MUST NOT create spans except by routing through this boundary. |
| **2. Spring auto-instrumented span lifecycle owner** | **OTel Java Agent** (production bytecode spans) and **Spring / Micrometer Observation** (framework observations: HTTP server/client conventions, `@Observed`, Actuator observation pipeline). TraceOperations MUST NOT assume ownership of these spans. |
| **3. Duplicate span prevention** | See [Duplicate-span prevention rules](#duplicate-span-prevention-rules) and [Implementation guardrails](#implementation-guardrails). Existing `suppress-micrometer-tracing` remains the HTTP-level guard when Agent is present ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)). `ObservationCoexistenceTest` (Slice 7) proves no competing unsynchronized roots for one operation. |
| **4. Micrometer tracing bridge vs MeteredTracingImplementation** | **`io.micrometer.tracing` bridge-otel** is an **optional dev/staging** Spring Actuator path ([MicrometerTracingMdcBridgeSmokeTest.java](../../platform-tracing-spring-boot-autoconfigure/src/test/java/space/br1440/platform/tracing/autoconfigure/mdc/MicrometerTracingMdcBridgeSmokeTest.java)), **not** the platform manual tracing engine. **`MeteredTracingImplementation`** records platform counters/histograms around **`TracingImplementation`** delegation only; it MUST NOT replace `TracingObservationHandler` and MUST NOT create spans. |
| **5. Spring tracing handlers** | **Leave framework handlers in place.** Platform MAY **customize** Observation conventions (`Platform*ObservationConvention`). Platform MAY **suppress** duplicate HTTP observations via opt-in `platform.tracing.suppression.suppress-micrometer-tracing` when Agent creates HTTP spans. Platform MUST NOT globally disable Observation for non-HTTP concerns without ADR amendment. Platform MUST NOT auto-wrap all Observations into `TraceOperations` spans. |
| **6. Coexistence tests** | **Existing:** `DuplicateSpansRegressionMatrixTest`, suppress tests, ArchUnit `NO_TRACED_AND_OBSERVED_ON_SAME_METHOD`. **Required (plan v3.4.2):** `ObservationCoexistenceTest`, `BeanTopologyTest`, `TracingImplementationRoutingTest`, `MeteredTopologyMatrixTest`, `DiagnosticsBoundaryTest`. |

---

## Implementation guardrails

These rules are normative for Slice 1A onward. Violations require ADR amendment or explicit architect sign-off.

- Manual platform spans MUST be created only through **`TracingImplementation.startSpan(SpanSpec)`**.
- Public entry points **`manual().operation(...)`**, **`manual().transport()...`**, **`manual().spanFromSpec(spec)`** MUST route to that boundary; they MUST NOT call OpenTelemetry `Tracer` / `SpanBuilder` directly.
- Public builders and facade types MUST NOT use OpenTelemetry `Tracer` directly (enforced by `TracingImplementationRoutingTest` + ArchUnit in Slice 2).
- **`MeteredTracingImplementation`** MUST decorate **`TracingImplementation`**; it MUST NOT create spans directly (delegate-only).
- **`MeteredTracingImplementation`** MUST NOT replace or wrap Spring **`TracingObservationHandler`** beans.
- **`MeteredTracingImplementation`** MUST NOT decorate the public **`TraceOperations`** facade (R01 lesson; no `@Primary` metered facade pattern).
- **`TraceOperations`** MUST NOT auto-wrap every Micrometer **`Observation`** into a platform manual span.
- Spring/Micrometer auto-observation MUST remain the owner of framework-level observations (HTTP server/client, `@Observed`, Observation handlers) unless a **later ADR** explicitly changes this.
- Explicit **`manual().operation(...)`** inside an auto-observed request MAY create a child/manual span; it MUST be consistently related to the active trace via OTel `Context.current()` unless `SpanSpec` explicitly requests ROOT/DETACHED under governance rules.
- No public API MUST expose OpenTelemetry SDK types.
- **`SpanSpec` / `SpanOptions` topology** (ROOT, DETACHED, CHILD, pre-start links) MUST remain intact per plan v3.4.2; no compatibility shim for v1 wide `TraceOperations` API.
- Durable fix for R01 is **structural SPI boundary**, not a patch to **`MeteredPlatformTracing`**.

---

## Detailed design rules

### Manual platform spans

All v3 manual entry points:

```text
manual().operation(...)
manual().transport()...
manual().spanFromSpec(spec)
```

MUST route to:

```text
TracingImplementation.startSpan(SpanSpec)
```

Rules:

- MUST use OpenTelemetry API at the implementation layer ([ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)).
- MUST NOT use Micrometer Observation API inside `TracingImplementation` or semantic transport builders ([ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md)).
- MUST NOT expose OpenTelemetry SDK types in public API.
- MAY create **child** manual spans inside an active auto-observed request trace (shared trace id via OTel `Context.current()`).
- MUST NOT create a **second unsynchronized root** for the same HTTP request / application operation unless `SpanSpec` explicitly requests ROOT and tests cover that scenario.

### Spring auto-observation

Spring Boot / Micrometer remain responsible for:

```text
HTTP server observations (WebMVC / WebFlux conventions)
HTTP client observations
framework-level @Observed observations
Micrometer Observation metrics handlers (when not suppressed)
```

Platform responsibilities on the auto path:

- Register / customize `Platform*ObservationConvention` beans (platform.type, platform.result).
- Apply suppress policy for Agent environments ([ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)).
- Emit startup diagnostics (`TracingObservationSuppressStartupRunner`, dual-channel drift).

Platform MUST NOT:

- Replace all Spring Observation with `traceOperations.manual()`.
- Create platform manual spans for every Observation event.

### MeteredTracingImplementation

Future internal decorator MUST:

- Decorate **`TracingImplementation`** only (not public `TraceOperations` facade).
- Increment platform metrics on manual tracing operations at SPI boundary.
- Preserve ROOT / DETACHED / CHILD / links topology (delegate-only span creation).
- MUST NOT create spans except via delegation to wrapped `TracingImplementation`.
- MUST NOT decorate or replace Spring `TracingObservationHandler` beans.
- MUST NOT reintroduce `@Primary` public-facade decorator pattern (R01).

### Duplicate-span prevention rules

1. **Separate intentional paths:** Agent/Observation auto spans vs platform manual spans — different owners, shared trace context when appropriate.
2. **HTTP Agent + Micrometer:** use `suppress-micrometer-tracing=true` when Agent instruments HTTP (accepted ADR).
3. **Method-level:** MUST NOT combine `@Traced` and `@Observed` on the same method (ArchUnit).
4. **@Traced aspect:** MUST NOT create HTTP server spans duplicating Agent/Observation ([TracedAspect.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/aspect/TracedAspect.java) documents this).
5. **Explicit manual only:** platform manual spans require developer call to `manual()` — no implicit wrapping of all Observations.
6. **Child inside request:** manual span inside auto-observed request SHOULD be CHILD unless `SpanSpec` requests ROOT/DETACHED with governance.
7. **No competing roots:** one HTTP request / one application operation MUST NOT yield two unsynchronized root spans (asserted by `ObservationCoexistenceTest`).

### Diagnostics and Actuator implications

Diagnostics MUST expose (via future `TracingDiagnostics` → `TracingDiagnosticsView`):

- Manual platform tracing state (`TracingState` mapped — not raw internal object).
- Whether metered implementation decoration is active.
- Whether Micrometer / Observation integration is present on classpath (boolean flags, not classloader dump).
- SDK mode / agent detection (extend existing `TracingActuatorEndpoint` patterns).

Diagnostics MUST NOT:

- Leak OpenTelemetry SDK types to Actuator JSON.
- Serialize internal `TracingState` directly without mapping.

---

## Test requirements

Future slices MUST implement (from plan v3.4.2):

### ObservationCoexistenceTest (Slice 7)

- With Spring/Micrometer Observation present, **one HTTP request or one application operation** MUST NOT produce **two unsynchronized root spans**.
- Explicit `manual()` operation inside auto-observed request MAY produce a **child/manual** span with consistent trace relationship to active context.
- Disabling/unavailable platform manual tracing MUST NOT disable unrelated Spring auto-observation unless explicitly configured.
- `MeteredTracingImplementation` MUST NOT create spans directly.
- No public-facade metered decorator path (`MeteredPlatformTracing` pattern forbidden post-refactor).

### BeanTopologyTest (Slice 2, autoconfigure)

- Exactly **one** active `TracingImplementation` chain.
- Micrometer classpath produces **`MeteredTracingImplementation`** decoration of that chain.
- No competing `@Primary` / `@Order` bypass of metered chain.

### TracingImplementationRoutingTest (Slice 2, core)

- All manual span creation paths route through **`TracingImplementation.startSpan(SpanSpec)`**.
- Public builders MUST NOT call OpenTelemetry `Tracer` directly (ArchUnit complement).

### MeteredTopologyMatrixTest (Slice 6)

- Metering preserves **ROOT**, **DETACHED**, **ROOT + links**.
- Invalid DETACHED + links fails fast (governance).
- Metric-count assertions **separate** from topology assertions (R12).

### DiagnosticsBoundaryTest (Slice 7)

- Actuator returns **`TracingDiagnosticsView`**, not internal `TracingState`.
- JSON contract stable for support tooling.

**Existing tests retained:** `DuplicateSpansRegressionMatrixTest`, suppress autoconfig tests, `MicrometerTracingMdcBridgeSmokeTest` (bridge-otel path only).

---

## Consequences

### Positive

- Clear ownership ends R01-class silent divergence for manual tracing.
- Compatible with agent-first and typed-span ADRs without reopening public API naming.
- Duplicate HTTP mitigation already proven; coexistence test closes R24 gap for v3 manual path.
- `MeteredTracingImplementation` scope is narrow and testable.

### Negative / trade-offs

- Developers must understand **three paths** (Agent, Observation, manual platform) and when to call `manual()`.
- Hybrid model requires Slice 7 matrix tests before production sign-off.
- Bridge-otel dev path remains distinct from production Agent path — documentation burden.

### Neutral

- Does not fix R01 in current v1 code (Slice 2/6).
- Does not implement v3 API (Slice 1A+).

---

## Non-goals

- No implementation of v3 API in this ADR.
- No production code changes.
- No decision to expose OpenTelemetry SDK types in public API.
- No decision to replace all Spring Observation with TraceOperations.
- No backward-compatibility wrapper for v1 `TraceOperations` API.
- No fix for R01 in this ADR (evidence only in Slice 0B).

---

## Open questions

These items are **operational or slice-scoped**. They do **not** block Slice 1A if Option C ownership (this ADR) is accepted. They MUST be resolved before the mapped slice ships.

| ID | Question | Blocks Slice 1A? | Resolve by slice |
|----|----------|:----------------:|------------------|
| OQ-1 | Fleet-default for `suppress-micrometer-tracing` in production Helm / deployment charts | No | Slice 7 / deployment policy |
| OQ-2 | Primary matrix for `ObservationCoexistenceTest`: Agent-on vs bridge-otel | No | Slice 7 (recommend Agent-on as primary production gate; bridge-otel as secondary smoke) |
| OQ-3 | Exact `TracingDiagnosticsView` JSON field names | No | Slice 7 |

### Open questions before Accepted status

Architects MAY accept Option C while leaving OQ-1–OQ-3 open, provided:

- ownership answers in [Required decision answers](#required-decision-answers) are signed off;
- [Acceptance checklist](#acceptance-checklist) is completed;
- no open question requires changing Slice 1A public API surface (none currently do).

If a future revision of OQ-2 or OQ-3 contradicts Option C ownership, this ADR MUST be re-opened before the affected slice merges.

---

## Impact on next slices

Aligned with [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) v3.4.2. No contradiction: manual paths, SPI boundary, internal metering, and test ownership match the plan.

### Slice 1A

- **Blocked** until this ADR status is **Accepted** and [Acceptance checklist](#acceptance-checklist) is signed off.
- MAY add **additive** public API skeleton (`TraceContextView`, `ManualTracing`, `SpanSpec`, builders) only after acceptance.
- MUST NOT introduce Micrometer Observation as the implementation of public manual builders.
- MUST preserve `SpanSpec` / `SpanOptions` / links topology required by the plan.
- MUST preserve no OTel SDK leak in public API.
- MUST NOT reintroduce wide v1 `TraceOperations` behavioral defaults or `MeteredPlatformTracing` as durable metering path.

### Slice 2

- MUST introduce **`TracingImplementation.startSpan(SpanSpec)`** as the single manual span creation boundary.
- MUST include **`TracingImplementationRoutingTest`** (core).
- MUST include **`BeanTopologyTest`** (Spring Boot autoconfigure).
- MUST prove **`MeteredTracingImplementation`** cannot be bypassed when Micrometer is present (one active chain; no competing `@Primary` / `@Order` bypass).
- MUST migrate anti-double guard and kill-switch to internal `TracingState` per plan.

### Slice 6

- MUST prove **`MeteredTracingImplementation`** preserves ROOT, DETACHED, ROOT+links.
- MUST prove invalid DETACHED+links fails fast (governance).
- MUST keep metric-count assertions separate from topology assertions (R12; `MeteredTopologyMatrixTest`).

### Slice 7

- MUST include **`ObservationCoexistenceTest`**.
- MUST prove Spring auto-observation and TraceOperations manual spans do not create two unsynchronized root spans for the same operation.
- MUST include **`DiagnosticsBoundaryTest`** and mapped **`TracingDiagnosticsView`** contract.
- MAY extend Spring Boot matrix beyond Slice 2 `BeanTopologyTest` (FilteredClassLoader, webmvc/webflux, `@Traced` against new facade).

### Slice 0A / 0B / 8

| Slice | Impact |
|-------|--------|
| **0A / 0B** | No change; R01 evidence stands ([R01.md](../known-issues/R01.md)) |
| **8** | Reference this ADR in platform docs; no API behavior change |

---

## Cross-check against plan v3.4.2

| Plan invariant | ADR alignment |
|----------------|---------------|
| `manual().operation(...)`, `manual().transport()`, `manual().spanFromSpec(spec)` | Confirmed as platform manual paths → `TracingImplementation.startSpan(SpanSpec)` |
| Single creation boundary | Confirmed |
| `MeteredTracingImplementation` decorates internal SPI, not public facade | Confirmed; `MeteredPlatformTracing` explicitly rejected as durable fix |
| `SpanOptions` topology/links | Confirmed intact |
| `ObservationCoexistenceTest` | Slice 7 requirement |
| `BeanTopologyTest` | Slice 2 autoconfigure proof |
| No v1 compatibility shim | Confirmed in guardrails |
| R01 fix via SPI, not facade patch | Confirmed |

---

## Acceptance checklist

- [x] Option C hybrid ownership model accepted.
- [x] Manual platform span lifecycle owner accepted: `TracingImplementation.startSpan(SpanSpec)`.
- [x] Spring auto-observation lifecycle owner accepted: OTel Agent + Spring/Micrometer Observation path.
- [x] `MeteredTracingImplementation` role accepted: metrics decorator only; no direct span creation; no replacement of Observation handlers.
- [x] Duplicate-span prevention rules accepted (suppress policy, ArchUnit, separate intentional paths, `ObservationCoexistenceTest`).
- [x] Spring tracing handlers policy accepted: leave in place; customize conventions; opt-in suppress only where documented.
- [x] Required tests accepted:
  - [x] `ObservationCoexistenceTest` (Slice 7)
  - [x] `BeanTopologyTest` (Slice 2)
  - [x] `TracingImplementationRoutingTest` (Slice 2)
  - [x] `MeteredTopologyMatrixTest` (Slice 6)
  - [x] `DiagnosticsBoundaryTest` (Slice 7)
- [x] Open questions OQ-1–OQ-3 classified as non-blocking for Slice 1A (or resolved).
- [x] Slice 1A unblock decision recorded (2026-07-06; approval source: architect approval communicated for v3.4.2 Slice 1A gate before implementation).

---

## Slice impact (summary table)

| Slice | Impact |
|-------|--------|
| **0A / 0B** | No change; R01 evidence stands |
| **1A** | **Unblocked** for additive API skeleton only (ADR Accepted) |
| **2** | `TracingImplementation`, routing + `BeanTopologyTest` |
| **6** | `MeteredTracingImplementation`, `MeteredTopologyMatrixTest` |
| **7** | `ObservationCoexistenceTest`, diagnostics DTO, extended matrix |
| **8** | Docs reference |

---

## References

- [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md) v3.4.2 — § Architecture Decision Required Before Slice 1A; R24; named test suites
- [platform-tracing-plan-final-architecture-review.md](../analysis/platform-tracing-plan-final-architecture-review.md) — P3 Observation ADR requirement
- [R01.md](../known-issues/R01.md) — MeteredPlatformTracing known defect
- [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md)
- [ADR-suppress-micrometer-tracing.md](./ADR-suppress-micrometer-tracing.md)
- [ADR-typed-span-api-semantic-layer.md](./ADR-typed-span-api-semantic-layer.md)
- [docs/tracing/anti-double-instrumentation.md](../tracing/anti-double-instrumentation.md)
- [docs/SUPPORTED.md](../SUPPORTED.md) — duplicate spans regression suite status
