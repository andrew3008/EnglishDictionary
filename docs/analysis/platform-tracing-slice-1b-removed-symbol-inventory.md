# PlatformTracing Slice 1B Removed-Symbol Inventory

> Pre-cutover inventory only. No production code, test code, or Gradle files were modified to produce this report.
> Canonical plan: [platform-tracing-refactoring-plan.md](platform-tracing-refactoring-plan.md) (v3.4.2). ADR: [ADR-platform-tracing-micrometer-observation-boundary.md](../decisions/ADR-platform-tracing-micrometer-observation-boundary.md) (Accepted).

## Executive summary

- Total findings (real symbol occurrences, false positives excluded from counts below): **86**
- REPLACE: **35**
- DELETE: **11** (class-level; see below for finer per-file breakdown)
- ISOLATE_V1_CHARACTERIZATION_TEST: **8** test classes (v1 evidence from Slice 0A/0B, plus additional pre-existing v1 API tests)
- ALLOW_REJECTED_NAME_DOC: **6** documentation files (plan §3.11, ADR text, prior review docs)
- ALLOW_EXISTING_V1_UNTIL_CUTOVER: **26** (declarations on `PlatformTracing.java` itself, kept until atomic cutover)
- REVIEW_REQUIRED: **0**
- FALSE_POSITIVE: **~140** (mostly `Tracer.spanBuilder(...).startSpan()` OpenTelemetry SDK calls in `platform-tracing-otel-extension`, `platform-tracing-test`, `platform-tracing-bench`, `platform-tracing-e2e-tests`; generic English word "justification" in unrelated docs/skills files; `OtelCurrentTraceContext` Micrometer class name mentioned in a Javadoc comment)
- High-risk modules: `platform-tracing-api` (facade + Facade builders + SpanRelation), `platform-tracing-spring-boot-autoconfigure` (MeteredPlatformTracing, TracingActuatorEndpoint, TracedAspect, KafkaBatchLinksAspect), `platform-tracing-core` (DefaultPlatformTracing, AbstractPlatformSpanBuilder, v1 characterization/baseline tests)
- Slice 1B may start: **YES**, subject to the Go/No-Go conditions below (no unresolved `REVIEW_REQUIRED` items were found).

### Slice 1B metering sequencing decision

Architect decision: delete `MeteredPlatformTracing` in Slice 1B and do not introduce a temporary public-facade metrics shim.

Rationale:
- v3 architecture forbids public-facade metered decorators.
- Temporary shims risk preserving the R01 failure pattern.
- Manual tracing self-metrics are allowed to have a temporary gap between Slice 1B and the later `MeteredTracingImplementation` work.
- Durable metrics return on the internal `TracingImplementation` boundary in Slice 2/Slice 6.

Constraint:
- No production code may reintroduce a `MeteredPlatformTracing`-style public facade decorator.

## Scope

Full repository search across all Gradle modules and `docs/`, restricted to source (`src/main`, `src/test`) trees; generated `build/` artifacts (javadoc HTML, test reports, binary test results) were excluded from classification because they are regenerated on every build and are not source-of-truth.

Modules inspected:

- `platform-tracing-api`
- `platform-tracing-core`
- `platform-tracing-spring-boot-autoconfigure`
- `platform-tracing-autoconfigure-webmvc`
- `platform-tracing-autoconfigure-webflux`
- `platform-tracing-spring-boot-starter-servlet` / `-reactive`
- `platform-tracing-otel-extension`
- `platform-tracing-test`
- `platform-tracing-e2e-tests`
- `platform-tracing-bench`, `platform-tracing-perf-harness`, `platform-tracing-perf-tests`
- `docs/**`

No `platform-tracing-samples` module exists in this repository (checked; not present). No action required for that path.

## Method

1. Ripgrep-style search (via the IDE's `Grep` tool, ripgrep-backed) for every symbol in the required list, first repository-wide, then narrowed to `*.java` and `*.md` to reduce noise from `build/` artifacts.
2. For every match in `platform-tracing-otel-extension`, `platform-tracing-test`, `platform-tracing-bench`, `platform-tracing-e2e-tests`, and `platform-tracing-perf-*` that matched `startSpan`, the call site was inspected directly to distinguish `PlatformTracing.startSpan(name, category)` from unrelated `io.opentelemetry.api.trace.Tracer.spanBuilder(...).startSpan()` (OTel SDK's own builder method, same literal name, different type). All confirmed OTel SDK calls were classified `FALSE_POSITIVE`.
3. For every match of generic English words (`justification`, `raw`, `advanced`, `current()`) context was read with `-C 1`/`-C 2` to rule out unrelated usage (e.g. `RetryPolicy`, `.cursor/skills/*.md`, comment text).
4. Declarations of old v1 API on `PlatformTracing.java` itself were catalogued once by line number rather than duplicated per caller.
5. Every remaining call site was classified per the categories mandated by this task.

## Inventory by symbol

### Current context access

| Symbol | File | Line/Context | Classification | Slice 1B action |
|---|---|---|---|---|
| `currentTraceId()` | `platform-tracing-api/src/main/java/.../PlatformTracing.java` | L35 (declaration) | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove declaration in 1B cutover |
| `currentTraceId()` | `platform-tracing-spring-boot-autoconfigure/.../actuator/TracingActuatorEndpoint.java` | L83 | REPLACE | `traceContext().traceId()` |
| `currentTraceId()` | `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredPlatformTracing.java` | L33 | DELETE | Class deleted in 1B (decorator removed; see High-risk findings) |
| `currentTraceId()` | `platform-tracing-autoconfigure-webmvc/.../servlet/TraceResponseHeaderServletFilter.java` | L116 | REPLACE | `traceContext().traceId()` |
| `currentTraceId()` | `platform-tracing-autoconfigure-webflux/.../reactive/TraceResponseHeaderWebFilter.java` | L51, L63 | REPLACE | `traceContext().traceId()` |
| `currentTraceId()` | `platform-tracing-core/src/test/.../OtelPlatformContextPropagationTest.java` | L67, L97, L136, L144, L159 | ISOLATE_V1_CHARACTERIZATION_TEST | v1 propagation characterization; isolate or migrate to `traceContext()` assertions |
| `currentTraceId()` | `platform-tracing-autoconfigure-webmvc/src/test/.../TraceResponseHeaderServletFilterTest.java` | L74,82,104,118,134,148,162 (mock stubs) | ISOLATE_V1_CHARACTERIZATION_TEST | v1 test mocks old facade; migrate together with production filter |
| `currentTraceId()`/`currentSpanId()` | `platform-tracing-spring-boot-autoconfigure/src/test/.../TracingAutoConfigurationContextTest.java` | L95, L133, L161 | ISOLATE_V1_CHARACTERIZATION_TEST | Tagged `r01-red`/topology test (Slice 0B evidence area) |
| `currentSpanId()` | `platform-tracing-api/src/main/java/.../PlatformTracing.java` | L41 (declaration) | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove declaration in 1B cutover |
| `currentSpanId()` | `platform-tracing-spring-boot-autoconfigure/.../actuator/TracingActuatorEndpoint.java` | L84 | REPLACE | `traceContext().spanId()` |
| `currentSpanId()` | `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredPlatformTracing.java` | L39 | DELETE | Class deleted in 1B |
| `currentSpanId()` | `platform-tracing-core/src/test/.../span/InternalSpanBuilderImplTest.java` | L85 | ISOLATE_V1_CHARACTERIZATION_TEST | v1 builder characterization |

### Generic span creation

| Symbol | File | Line/Context | Classification | Slice 1B action |
|---|---|---|---|---|
| `startSpan(name, category)` (abstract) | `PlatformTracing.java` | L60 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; primary replacement `manual().operation(name).start/run/call` |
| `startSpan(name, category, relation)` (default) | `PlatformTracing.java` | L73-76 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove (root cause of R01 default-dispatch) |
| `startRootSpan(...)` (default) | `PlatformTracing.java` | L83-84 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `manual().operation(name).root().start/run/call` |
| `startChildSpan(...)` (default) | `PlatformTracing.java` | L91-92 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `manual().operation(name).child().start/run/call` |
| `startDetachedSpan(...)` (default) | `PlatformTracing.java` | L99-100 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `manual().operation(name).detached().start/run/call` |
| `startSpanWithLinks(...)` (default) | `PlatformTracing.java` | L111-114 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `.root().linkedTo(...)` (ROOT+links only; CHILD+links forbidden in v1) |
| `addLink(...)` (default no-op) | `PlatformTracing.java` | L120 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; no post-start replacement — pre-start `linkedTo(...)` only |
| `inSpan(...)` × 4 overloads (default) | `PlatformTracing.java` | L283, L308, L327, L345 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `run()/call()/callChecked()` |
| `startSpan(name, category)` | `DefaultPlatformTracing.java` (concrete impl) | (implements abstract method) | REPLACE (impl migrates to `TracingImplementation.startSpan(SpanSpec)` in Slice 2, not 1B) | Out of 1B scope; noted for Slice 2 planning |
| `addLink(...)` | `DefaultPlatformTracing.java` | L182, L225 (calls into OTel `SpanBuilder.addLink`) | REVIEW_REQUIRED → resolved FALSE_POSITIVE for symbol-removal purposes | This is `SpanBuilder.addLink` (OTel SDK), invoked *inside* the v1 implementation of the platform `addLink`/link-handling logic — implementation detail, not the public `PlatformTracing.addLink` call site. Will be reworked when `DefaultTracingImplementation` is built in Slice 2. |
| `addLink(...)` | `AbstractPlatformSpanBuilder.java` (via `KafkaBatchLinksAspect.java` L64) | L64 | FALSE_POSITIVE for public symbol removal | OTel `SpanBuilder.addLink`, not `PlatformTracing.addLink` |
| `startSpan(name, category)` | `TracedAspect.java` | L101 | REPLACE | `manual().operation(spanName).start()`/`.run()` depending on aspect semantics — Slice 7 wiring change |
| `startSpan(name, category)` | `TracingCoreAutoConfiguration.java` | L149 (`.startSpan()` on OTel `SpanBuilder`) | FALSE_POSITIVE | OTel SDK call, not `PlatformTracing` |
| `startSpan(...)` | `platform-tracing-perf-tests/.../OrdersController.java` | L43, L59 | REPLACE | `manual().operation(name).start/run/call` — sample/perf harness code |
| `startSpan(...)` | `platform-tracing-perf-harness/.../PerfEndpointsController.java` | L38, L52, L67, L81 | REPLACE | `manual().operation(name).start/run/call` |
| `startSpan(...)` | `platform-tracing-bench/.../TypedBuilderBenchmark.java`, `CompositePipelineBenchmark.java` (×4), `TracedAspectBenchmark.java`, `StartSpanBenchmark.java` | multiple | REPLACE | JMH benchmarks call `PlatformTracing.startSpan`; migrate to v3 API or isolate as v1 benchmark baseline until Slice 1B lands |
| `startSpan(...)` | `platform-tracing-bench/.../ValidatingSpanProcessorBenchmark.java`, `SpanLimitsBenchmark.java`, `QueueOfferBenchmark.java` | multiple | FALSE_POSITIVE | OTel SDK `Tracer.spanBuilder(...).startSpan()`, not `PlatformTracing` |
| `startSpan(...)` | `platform-tracing-e2e-tests/.../ExceptionEventScrubbingE2ETest.java` | L137, L195 | REPLACE | `manual().operation(name).start/run/call` |
| `inSpan(...)` | `platform-tracing-e2e-tests/.../ExceptionEventScrubbingE2ETest.java` | L170 | REPLACE | `.run(...)` |
| `startSpan(...)` | `platform-tracing-e2e-tests/.../CollectorProductionPolicyE2ETest.java`, `TracingE2ETest.java` (×8), `ResourceIdentityAgentSmokeMain.java` | multiple | FALSE_POSITIVE | OTel SDK `SpanBuilder.startSpan()` |
| `startSpan(...)` | `platform-tracing-e2e-tests/.../smoke/BspOverflowSafetyMain.java` | L48 | FALSE_POSITIVE | OTel SDK `Tracer.spanBuilder(...).startSpan()` |
| `startSpan(...)` | `platform-tracing-core/src/test/.../exception/ExceptionRecorderTest.java` | L92, L127 | FALSE_POSITIVE | OTel SDK call |
| `startSpan(...)` | `platform-tracing-core/src/test/.../span/SpanEnricherTest.java` | L49, L87 | FALSE_POSITIVE | OTel SDK call |
| `startSpan(...)` | `platform-tracing-core/src/test/.../bsp/BatchSpanProcessorOverflowPolicyProbeTest.java` | L133 | FALSE_POSITIVE | OTel SDK call |
| `startSpan(...)` | `platform-tracing-spring-boot-autoconfigure/src/test/.../errorhandling/TracingRequestContextSupplierTest.java` | L63 | FALSE_POSITIVE | OTel SDK call |
| `startSpan(...)` | `platform-tracing-spring-boot-autoconfigure/src/test/.../aspect/TracedAspectTest.java` | L96, L133 | ISOLATE_V1_CHARACTERIZATION_TEST | Tests `TracedAspect` against v1 `PlatformTracing.startSpan`; migrates together with production `TracedAspect` in Slice 7 |
| `startChildSpan(...)` | `platform-tracing-core/src/test/.../OtelPlatformContextPropagationTest.java` | L158 | ISOLATE_V1_CHARACTERIZATION_TEST | v1 propagation characterization test |
| `startRootSpan(...)`, `startSpanWithLinks(...)`, `inSpan(...)` | `platform-tracing-spring-boot-autoconfigure/src/test/.../TracingAutoConfigurationContextTest.java` | L94, L131, L160 | ISOLATE_V1_CHARACTERIZATION_TEST | R01-adjacent bean-topology test; part of Slice 0B evidence area, must remain isolated through cutover |
| `startSpan(...)` (various) | `platform-tracing-otel-extension/**` (30+ test files) | many | FALSE_POSITIVE | All confirmed OTel SDK `Tracer.spanBuilder(...).startSpan()`; this module tests span processors directly against the OTel SDK, not `PlatformTracing` |
| `startSpan(...)` (Javadoc example) | `platform-tracing-test/.../junit/OtelSdkTest.java` | L33 | FALSE_POSITIVE | Javadoc usage example of OTel SDK, not `PlatformTracing` |
| `startSpan(...)` | `platform-tracing-test/src/test/**` (8 files) | many | FALSE_POSITIVE | OTel SDK extension tests |
| `startSpan(...)` | `platform-tracing-autoconfigure-webflux/src/test/.../ReactorContextPropagationIntegrationTest.java` | L67 | FALSE_POSITIVE | `openTelemetry.getTracer(...).spanBuilder(...).startSpan()` |

### Typed start shortcuts

| Symbol | File | Line | Classification | Slice 1B action |
|---|---|---|---|---|
| `startHttpServer` | `PlatformTracing.java` | L128-129 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `manual().operation(name)` or `manual().transport().http()` in Slice 3A |
| `startHttpClient` | `PlatformTracing.java` | L136-137 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; same as above |
| `startDb` | `PlatformTracing.java` | L144-145 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `manual().transport().database()` (Slice 3B) |
| `startRpcServer` | `PlatformTracing.java` | L152-153 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `manual().transport().rpc()` (Slice 3C) |
| `startRpcClient` | `PlatformTracing.java` | L160-161 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; same as above |
| `startInternal` | `PlatformTracing.java` | L171-172 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; replacement `manual().operation(name)` |

No call sites of these typed shortcuts were found outside `PlatformTracing.java` itself and its own default-method bodies (they delegate to `startSpan(name, category)` internally).

### Builder factory methods

| Symbol | File | Line | Classification | Slice 1B action |
|---|---|---|---|---|
| `internalSpan()` | `PlatformTracing.java` | L183 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove; returns `Facade*` builder (bypasses validation) |
| `httpServerSpan()` | `PlatformTracing.java` | L198 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove |
| `httpClientSpan()` | `PlatformTracing.java` | L206 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove |
| `databaseSpan()` | `PlatformTracing.java` | L214 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove |
| `rpcServerSpan()` | `PlatformTracing.java` | L222 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove |
| `rpcClientSpan()` | `PlatformTracing.java` | L230 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove |
| `kafkaProducerSpan()` | `PlatformTracing.java` | L238 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove |
| `kafkaConsumerSpan()` | `PlatformTracing.java` | L246 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Remove |
| `internalSpan()` (impl usage) | `platform-tracing-core/src/test/.../span/InternalSpanBuilderImplTest.java`, `EscapeHatchSpanBuilderTest.java` | multiple | ISOLATE_V1_CHARACTERIZATION_TEST | v1 builder-factory characterization tests |

### Old relation/decorator/facade classes

| Symbol | File | Classification | Slice 1B action |
|---|---|---|---|
| `SpanRelation` (enum, `ROOT/CHILD/DETACHED`) | `platform-tracing-api/.../span/SpanRelation.java` | ALLOW_EXISTING_V1_UNTIL_CUTOVER | DELETE in Slice 1B per plan; superseded by `Topology` (already added additively in Slice 1A under `api.span.spec`) |
| `SpanRelation` usage | `PlatformTracing.java` (L84, L92, L100) | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Removed together with default methods |
| `SpanRelation` usage | `DefaultPlatformTracing.java` | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Migrated to `Topology`/`SpanOptions` when `DefaultTracingImplementation` lands (Slice 2); v1 impl kept intact through 1B per plan (only facade cuts over in 1B; core impl detail migration is Slice 2 per plan text, but the `PlatformTracing`-facing surface must compile against the new facade — see High-risk findings) |
| `SpanRelation` usage | `platform-tracing-core/src/test/.../DefaultPlatformTracingTest.java`, `DefaultPlatformTracingInSpanTest.java` | ISOLATE_V1_CHARACTERIZATION_TEST | v1 tests; migrate or isolate per plan §5 Slice 1B notes |
| `SpanRelation` usage | `platform-tracing-core/src/test/.../DefaultPlatformTracingBaselineTest.java` | ISOLATE_V1_CHARACTERIZATION_TEST | Slice 0A GREEN baseline evidence — must remain isolated, not deleted |
| `SpanRelation` usage | `platform-tracing-core/src/test/.../MeteredPlatformTracingKnownDefectTest.java` | ISOLATE_V1_CHARACTERIZATION_TEST | Slice 0B RED known-defect evidence (`r01-red`) — must remain isolated, not deleted, until Slice 6 v3 topology tests replace R01 evidence per plan lifecycle rules |
| `SpanRelation` usage | `platform-tracing-spring-boot-autoconfigure/src/test/.../TracingAutoConfigurationContextTest.java` | ISOLATE_V1_CHARACTERIZATION_TEST | Same R01-adjacent bean-topology evidence |
| `MeteredPlatformTracing` (class) | `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredPlatformTracing.java` | DELETE (forbidden to modify in this task; flagged for Slice 1B/2) | Plan requires this class deleted; decorator moves to `MeteredTracingImplementation` on the new `TracingImplementation` SPI (Slice 2/6) |
| `MeteredPlatformTracing` reference | `TracingMetricsAutoConfiguration.java` (`@Primary` bean registration) | DELETE (wiring) | Must be rewired to new SPI decorator; **highest-risk R01 wiring point** |
| `MeteredPlatformTracing` reference | `PlatformTracingMetrics.java` | REVIEW_REQUIRED → resolved REPLACE | Metrics counting logic needs to move to the new metering boundary at `TracingImplementation`; not a simple rename |
| `MeteredPlatformTracing` reference | `platform-tracing-core/src/test/.../MeteredPlatformTracingKnownDefectTest.java` | ISOLATE_V1_CHARACTERIZATION_TEST | Slice 0B RED evidence, keep until Slice 6 |
| `MeteredPlatformTracing` reference | `platform-tracing-spring-boot-autoconfigure/src/test/.../TracingAutoConfigurationTest.java`, `TracingAutoConfigurationContextTest.java`, `metrics/PlatformTracingMetricsTest.java` | ISOLATE_V1_CHARACTERIZATION_TEST | Bean-wiring assertions on old `@Primary` decorator; superseded by `BeanTopologyTest` in Slice 2 |
| `Facade*SpanBuilder` (8 classes: `FacadeInternalSpanBuilder`, `FacadeHttpServerSpanBuilder`, `FacadeHttpClientSpanBuilder`, `FacadeDatabaseSpanBuilder`, `FacadeRpcServerSpanBuilder`, `FacadeRpcClientSpanBuilder`, `FacadeKafkaProducerSpanBuilder`, `FacadeKafkaConsumerSpanBuilder`) | `platform-tracing-api/.../span/builder/` | DELETE | Explicit plan requirement (§2 repository facts: "9 классов для удаления в enabled-режиме") |
| `AbstractFacadeTypedSpanBuilder` | `platform-tracing-api/.../span/builder/AbstractFacadeTypedSpanBuilder.java` | DELETE | Same as above; 9th class |

### Public `recordException` path

| Symbol | File | Classification | Slice 1B action |
|---|---|---|---|
| `recordException(...)` (abstract) | `PlatformTracing.java` L253 | ALLOW_EXISTING_V1_UNTIL_CUTOVER | Method survives conceptually on `SpanHandle`/`TracingImplementation` in v3 (already added additively as `SpanHandle.recordException` in Slice 1A); old facade-level declaration removed at cutover |
| `recordException(...)` | `DefaultPlatformTracing.java`, `MeteredPlatformTracing.java` (impl) | ALLOW_EXISTING_V1_UNTIL_CUTOVER / DELETE (Metered class) | Impl migrates to `TracingImplementation.recordException` boundary in Slice 2 |
| `recordException(...)` | `SpanScope.java` (separate v1 type, not `PlatformTracing`) | FALSE_POSITIVE for `PlatformTracing`-facade removal | `SpanScope.recordException` is a distinct v1 handle-level method; superseded by v3 `SpanHandle.recordException` (already exists). `SpanScope` itself is not in the required removal list and is out of Slice 1B scope unless the plan's Slice 1B text says otherwise (it does not explicitly list `SpanScope` for deletion) |
| `recordException(...)` | `platform-tracing-core/src/test/.../DefaultPlatformTracingBaselineTest.java` | ISOLATE_V1_CHARACTERIZATION_TEST | Slice 0A GREEN baseline evidence |
| `recordException(...)` | `platform-tracing-e2e-tests/.../ExceptionEventScrubbingE2ETest.java` | REPLACE | Migrate to `SpanHandle.recordException` via v3 API when e2e test is ported |
| `recordException(...)` | `platform-tracing-test/.../arch/OtelDirectIntegrationRules.java` | FALSE_POSITIVE | ArchUnit rule name (`NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER`) references a different, unrelated internal `ExceptionRecorder` concept, not the public `PlatformTracing.recordException` facade method |
| `recordException(...)` | `platform-tracing-spring-boot-autoconfigure/.../aspect/TracedAspect.java` | REPLACE | Aspect currently calls `SpanScope.recordException`; migrates alongside `TracedAspect` rewiring in Slice 7 |

### Rejected/stale names

| Symbol | File | Classification | Notes |
|---|---|---|---|
| `CurrentTraceContext` | none found in `*.java` source | FALSE_POSITIVE (not present) | Only appears as `OtelCurrentTraceContext` inside a Javadoc comment in `TracingMdcKeys.java` referencing the *Micrometer* class of that name — unrelated external type, not our own rejected symbol |
| `businessSpan` | none found | — | Not present anywhere in the repository |
| `ManualInstrumentation` / `manualInstrumentation` | none found | — | Not present |
| `instrumented` / `InstrumentedTracing` | none found | — | Not present |
| `spans` (as public method) | none found | — | Not present as a method name |
| `AdvancedTracing` / `advanced()` | none found | — | Not present |
| `escapeHatch` | `EscapeHatchArchRuleTest.java`, `EscapeHatchSpanBuilderTest.java`, `TracingArchRules.java` (`ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION`), `docs/tracing/anti-double-instrumentation.md` | ALLOW_EXISTING_V1_UNTIL_CUTOVER / FALSE_POSITIVE | This is an existing v1 *governance concept* for `Facade*` builders requiring `@SuppressEscapeHatchWarning`-style annotations, not the rejected public API name `escapeHatch()` method described in plan §3.11. It is coupled to the `Facade*` builders being deleted, so it naturally goes away with them; not independently actionable |
| `customSpan` | none found | — | Not present |
| `rawSpan` | none found | — | Not present |
| `raw` (as public API method) | no method named `raw()` found; matches were `SqlSanitizer`/other unrelated identifiers/comments | FALSE_POSITIVE | — |
| `justification` | `ReviewPromptBuilder.java` (opus-mcp-server, unrelated tool), `.cursor/skills/backend.md`, `.cursor/skills/security.md`, misc `docs/refactoring/**` (unrelated packages), `docs/architecture/platform-tracing-fitness-functions.md`, `docs/architecture/platform-tracing-control-second-stage-refactoring-dossier.md` | FALSE_POSITIVE | Generic English word usage ("without justification"), not the rejected `SpanSpec.justification(String)` API |
| `execute` (as public top-level method) | none found on `PlatformTracing` or builders | — | Not present; already avoided |

## Inventory by module

| Module | Findings | Main action |
|---|---:|---|
| `platform-tracing-api` | 26 (declarations) + 9 (Facade/Abstract classes) = 35 | Delete v1 default methods and 9 Facade classes from `PlatformTracing.java`/`span/builder/`; delete `SpanRelation.java` |
| `platform-tracing-core` | 5 production call sites + 9 test classes with v1 characterization usage | Migrate `DefaultPlatformTracing`/`AbstractPlatformSpanBuilder` internals in Slice 2 (not 1B); isolate/migrate v1 tests |
| `platform-tracing-spring-boot-autoconfigure` | 8 production call sites (`TracingActuatorEndpoint`, `MeteredPlatformTracing`, `TracingMetricsAutoConfiguration`, `KafkaBatchLinksAspect`, `TracedAspect`) + 4 test classes | Highest-risk module: delete/rewire `MeteredPlatformTracing` and its `@Primary` registration |
| `platform-tracing-autoconfigure-webmvc` | 1 production call site (`TraceResponseHeaderServletFilter`) + 1 test class | Replace `currentTraceId()` with `traceContext().traceId()` |
| `platform-tracing-autoconfigure-webflux` | 2 production call sites (`TraceResponseHeaderWebFilter`) | Replace `currentTraceId()` with `traceContext().traceId()` |
| `platform-tracing-otel-extension` | 0 real findings (all `startSpan` matches are OTel SDK, confirmed false positive) | No action |
| `platform-tracing-test` | 0 real findings (Javadoc example + OTel SDK extension tests) | No action |
| `platform-tracing-e2e-tests` | 3 real findings (`ExceptionEventScrubbingE2ETest`), rest false positive (OTel SDK) | Migrate one e2e test file |
| `platform-tracing-bench` | ~7 real findings (`TypedBuilderBenchmark`, `CompositePipelineBenchmark`, `TracedAspectBenchmark`, `StartSpanBenchmark`), rest false positive (OTel SDK) | Migrate benchmarks or isolate as v1 baseline benches until 1B lands |
| `platform-tracing-perf-tests` / `platform-tracing-perf-harness` | 6 real findings | Migrate sample controllers to v3 API alongside cutover |
| `platform-tracing-spring-boot-starter-servlet` / `-reactive` | 0 findings | No source in these modules besides re-export; no action |
| `docs/**` | 6 documentation files with rejected-name mentions (allowed) | No action — these are explanatory/historical, not code |

## High-risk findings

- **Old `PlatformTracing` wide API declarations** (`platform-tracing-api/src/main/java/.../PlatformTracing.java`): ~26 default/abstract methods spanning current-context access, generic/typed span creation, links, and `inSpan`. This is the single file that defines the entire v1 surface being cut over; the Slice 1B PR diff for this file is the crux of the atomic cutover.
- **`MeteredPlatformTracing`** (`platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredPlatformTracing.java`): registered `@Primary` in `TracingMetricsAutoConfiguration.java` whenever Micrometer is on the classpath. This is the confirmed root cause of R01 (silent ROOT/DETACHED/links degradation via default-method dispatch). Deleting this class without simultaneously re-wiring metering onto the new `TracingImplementation` boundary would either (a) leave metrics uncollected, or (b) require a temporary bridge — plan text places the durable metering fix in Slice 2/6, so Slice 1B must keep the module compiling in some transitional state or defer metering-bean wiring changes explicitly (see Go/No-Go conditions).
- **`Facade*SpanBuilder` (8 classes) + `AbstractFacadeTypedSpanBuilder`** (`platform-tracing-api/.../span/builder/`): all bypass semconv validation and the anti-double guard per plan §2 repository facts. Confirmed present, confirmed only referenced from `PlatformTracing.java` default methods and their own test files (`EscapeHatchSpanBuilderTest.java`, `InternalSpanBuilderImplTest.java`). Safe to delete once `PlatformTracing.java` no longer exposes `internalSpan()`/`httpServerSpan()`/etc.
- **`SpanRelation`**: still referenced by `PlatformTracing.java`, `DefaultPlatformTracing.java`, and 4 test classes. The v3 `Topology` enum (already added in Slice 1A under `api.span.spec`) is the intended replacement; `SpanRelation` must not be deleted before every reference above is migrated or isolated, per this task's explicit constraint ("Do not delete SpanRelation").
- **`inSpan` default methods** (`PlatformTracing.java` L283-358): four overloads, all default, explicitly documented in-code as forbidden to override in decorators ("Переопределение inSpan в декораторах ЗАПРЕЩЕНО"). This is tribal-knowledge enforcement rather than a structural guarantee — exactly the R01 pattern. Confirmed low production call-site count (`TracedAspect`, one e2e test) — migration is tractable.
- **`addLink` default no-op** (`PlatformTracing.java` L120): confirmed default method is a no-op; the only real span-linking logic lives inside `DefaultPlatformTracing`'s internal `addLink(SpanBuilder, ...)`-style calls to the OTel SDK, which are implementation-internal, not the public no-op. No production caller of the public `PlatformTracing.addLink(...)` was found outside `PlatformTracing.java` itself, which reduces migration risk for this specific symbol.
- **Tests that may accidentally become false-green after cutover**: `MeteredPlatformTracingKnownDefectTest.java` and the R01-tagged sections of `TracingAutoConfigurationContextTest.java` are RED-by-design (`@Tag("r01-red")`, `knownDefectTest` Gradle task). If Slice 1B accidentally deletes `MeteredPlatformTracing` without also removing/updating these RED tests' registration in the `knownDefectTest` source set, the `knownDefectTest` task could fail to compile (false red-for-wrong-reason) or, worse, silently stop running (false green) if the task definition is not updated in lockstep. This must be an explicit checklist item in the Slice 1B PR, not incidental.
- **Autoconfiguration references to the old facade**: `TracingCoreAutoConfiguration.java`, `TracingMetricsAutoConfiguration.java`, `TracingActuatorEndpoint.java`, `TracedAspect.java`, `KafkaBatchLinksAspect.java` in `platform-tracing-spring-boot-autoconfigure` all reference `PlatformTracing` v1 methods directly. All five must compile against the new narrow facade (`traceContext()` + `manual()`) simultaneously — this is the definition of "atomic" cutover in the plan and confirms Slice 1B cannot be split further without a compilation-breaking intermediate state.

## Replacement map

| Old API | Primary replacement | Exceptional replacement | Notes |
|---|---|---|---|
| `currentTraceId()` | `traceContext().traceId()` | n/a | Optional semantics preserved |
| `currentSpanId()` | `traceContext().spanId()` | n/a | Optional semantics preserved |
| `startSpan(name, category)` | `manual().operation(name).start/run/call` | `manual().spanFromSpec(spec)` only when semantic builders do not cover the use case | `spanFromSpec` is governed, not co-equal |
| `startSpan(name, category, relation)` | `manual().operation(name).{root/child/detached}().start/run/call` | `manual().spanFromSpec(spec)` if needed | Topology is now explicit |
| `startRootSpan(...)` | `manual().operation(name).root().start/run/call` | `spanFromSpec` only if needed | |
| `startChildSpan(...)` | `manual().operation(name).child().start/run/call` | `spanFromSpec` only if needed | |
| `startDetachedSpan(...)` | `manual().operation(name).detached().start/run/call` | `spanFromSpec` only if needed | |
| `startSpanWithLinks(...)` | `.root().linkedTo(...).start/run/call` | `spanFromSpec` if policy requires | `CHILD + links` forbidden in v1 |
| `addLink(...)` | pre-start `linkedTo(...)` on builder | no post-start replacement | Post-start links removed entirely |
| `inSpan(...)` (4 overloads) | `run()` / `call()` / `callChecked()` | n/a | Terminal methods on `PlatformSpanBuilder`/`SpecifiedSpan` |
| `startHttpServer(name)` / `startHttpClient(name)` | `manual().transport().http()...` (Slice 3A) | `manual().operation(name)` if transport builder not yet available | |
| `startDb(name)` | `manual().transport().database()...` (Slice 3B) | `manual().operation(name)` | |
| `startRpcServer(name)` / `startRpcClient(name)` | `manual().transport().rpc()...` (Slice 3C) | `manual().operation(name)` | |
| `startInternal(name)` | `manual().operation(name)` | n/a | |
| `internalSpan()` / `httpServerSpan()` / `httpClientSpan()` / `databaseSpan()` / `rpcServerSpan()` / `rpcClientSpan()` / `kafkaProducerSpan()` / `kafkaConsumerSpan()` | `manual().operation(...)` or `manual().transport()...` | n/a | Old factories return validation-bypassing `Facade*` builders — no direct 1:1 replacement, must go through `manual()` |
| `recordException(throwable)` (facade-level) | `SpanHandle.recordException(throwable)` (already added Slice 1A) | n/a | Exactly-once policy defined in Slice 4 |
| `SpanRelation.ROOT/CHILD/DETACHED` | `Topology.ROOT/CHILD/DETACHED` (already added Slice 1A under `api.span.spec`) | n/a | `SpanRelation` deleted only after every reference migrates |
| `MeteredPlatformTracing` (decorator class) | `MeteredTracingImplementation` decorating `TracingImplementation` (Slice 2/6) | n/a | Not a 1:1 rename — decoration boundary moves from facade to SPI |
| `Facade*SpanBuilder` (8 classes) + `AbstractFacadeTypedSpanBuilder` | Deleted; semantic transport builders under `manual().transport()` (Slice 3A-3C) | n/a | No replacement class — functionality replaced by governed builders |

## V1 characterization tests to isolate or migrate

| Test class | Module | Origin | Disposition |
|---|---|---|---|
| `DefaultPlatformTracingBaselineTest` | `platform-tracing-core` | Slice 0A GREEN baseline | Isolate as permanent v1 evidence, or migrate assertions to v3 API and retire once v3 equivalents exist per plan lifecycle rules |
| `MeteredPlatformTracingKnownDefectTest` | `platform-tracing-core` | Slice 0B RED (`r01-red`), `knownDefectTest` task | Keep isolated (RED-by-design) until Slice 6 v3 metered topology tests supersede it; do not "fix" it to pass |
| `DefaultPlatformTracingTest` | `platform-tracing-core` | Pre-existing v1 unit test | Migrate to v3 API assertions or isolate in a non-default source set at cutover |
| `DefaultPlatformTracingInSpanTest` | `platform-tracing-core` | Pre-existing v1 unit test | Same as above; covers `inSpan` semantics specifically |
| `OtelPlatformContextPropagationTest` | `platform-tracing-core` | Pre-existing v1 unit test | Uses `currentTraceId()`/`startChildSpan()`; migrate to `traceContext()` |
| `InternalSpanBuilderImplTest` | `platform-tracing-core` | Pre-existing v1 builder test | Covers `internalSpan()` factory being deleted; migrate or retire alongside `Facade*` deletion |
| `EscapeHatchSpanBuilderTest` | `platform-tracing-core` | Pre-existing v1 builder test | Covers escape-hatch governance on `Facade*` builders; retire alongside `Facade*` deletion (governance concept moves to `spanFromSpec` per plan) |
| `TracingAutoConfigurationContextTest` | `platform-tracing-spring-boot-autoconfigure` | Bean-topology/R01-adjacent test | Isolate; superseded by `BeanTopologyTest` (Slice 2 hard gate) |
| `TracingAutoConfigurationTest` | `platform-tracing-spring-boot-autoconfigure` | Asserts `@Primary` is `MeteredPlatformTracing` | Must be rewritten when `MeteredPlatformTracing` is deleted — this assertion will otherwise fail to compile, which is expected and correct |
| `PlatformTracingMetricsTest` | `platform-tracing-spring-boot-autoconfigure` | Tests metrics decorator | Migrate to test `MeteredTracingImplementation` once it exists (Slice 6); until then keep isolated |
| `TracedAspectTest` | `platform-tracing-spring-boot-autoconfigure` | Tests `TracedAspect` against v1 facade | Migrate together with `TracedAspect` production rewiring (Slice 7) |
| `TraceResponseHeaderServletFilterTest` | `platform-tracing-autoconfigure-webmvc` | Mocks `currentTraceId()` | Migrate mock to `traceContext().traceId()` when filter is updated |

## Documentation-only allowed occurrences

These files intentionally document rejected/old names and require no change:

- `docs/analysis/platform-tracing-refactoring-plan.md` — §3.11 Rejected names table, §4 breaking-changes/replacement map, §5 slice descriptions (all explanatory)
- `docs/decisions/ADR-platform-tracing-micrometer-observation-boundary.md` — repository-facts table referencing `MeteredPlatformTracing`/`DefaultPlatformTracing` as evidence
- `docs/analysis/platform-tracing-plan-final-architecture-review.md` — adversarial review referencing old symbols as findings
- `docs/analysis/platform-tracing-api-platformtracing-investigation.md` — original investigation baseline
- `docs/architecture/platform-tracing-classes.puml`, `docs/architecture/Components_v2.puml` — architecture diagrams depicting current (pre-cutover) class structure; expected to be regenerated after Slice 1B, not before
- `docs/refactoring/refactoring-plan.md`, `docs/jira/subtasks-wiki.md` — historical/planning documents referencing v1 names for context

No action needed for any of the above in Slice 1B.

## Review-required occurrences

None. All findings were resolved to one of DELETE / REPLACE / ISOLATE_V1_CHARACTERIZATION_TEST / ALLOW_REJECTED_NAME_DOC / ALLOW_EXISTING_V1_UNTIL_CUTOVER / FALSE_POSITIVE during this inventory pass. Two ambiguous cases were investigated and resolved rather than left open:

1. `addLink` calls inside `DefaultPlatformTracing.java`/`AbstractPlatformSpanBuilder.java`/`KafkaBatchLinksAspect.java` — resolved as `FALSE_POSITIVE` for public-symbol removal purposes (they invoke `io.opentelemetry.api.trace.SpanBuilder.addLink`, an OTel SDK method with the same name as the deprecated public `PlatformTracing.addLink`, but a different type and call site).
2. `PlatformTracingMetrics.java` reference to `MeteredPlatformTracing` — resolved as `REPLACE` (not `DELETE`) because the metrics-counting logic itself must move to the new `TracingImplementation` boundary rather than simply disappearing.

## Slice 1B cutover recommendations

1. Treat `platform-tracing-api/src/main/java/.../PlatformTracing.java` as the single source-of-truth diff: replace its ~26 declarations with `traceContext()` + `manual()` per plan §3.1, in one atomic commit together with deletion of the 9 `Facade*`/`AbstractFacadeTypedSpanBuilder` classes and `SpanRelation.java`.
2. Because `MeteredPlatformTracing` deletion and its `@Primary` re-wiring is entangled with the not-yet-built `TracingImplementation`/`MeteredTracingImplementation` SPI (Slice 2/6 per plan), Slice 1B must decide **one** of:
   - (a) delete `MeteredPlatformTracing` and temporarily leave Micrometer metrics uncollected until Slice 6, documenting this explicitly as an accepted regression window, or
   - (b) keep a minimal compiling metrics shim in `platform-tracing-spring-boot-autoconfigure` scoped only to counting `manual()`-path spans until Slice 6 lands.
   This decision is **not resolved by this inventory** and should be made explicitly by the architect reviewing the Slice 1B PR; the plan text is ambiguous on this specific ordering nuance since `TracingImplementation` is nominally a Slice 2 deliverable but `MeteredPlatformTracing` deletion is written as Slice 1B scope.
3. Update the `knownDefectTest` Gradle source set/task registration in `platform-tracing-core/build.gradle` and `platform-tracing-spring-boot-autoconfigure/build.gradle` in the same PR as any change that could affect `MeteredPlatformTracingKnownDefectTest` or `TracingAutoConfigurationContextTest` compilation, to avoid the false-green/false-red risk noted above.
4. Migrate or isolate the 12 v1 test classes listed above before merging; do not delete Slice 0A/0B evidence tests (`DefaultPlatformTracingBaselineTest`, `MeteredPlatformTracingKnownDefectTest`) — isolate into a non-default source set or leave running as-is if they still compile against a compatibility path, per plan lifecycle rules.
5. Update the 5 confirmed production call sites in `platform-tracing-spring-boot-autoconfigure` (`TracingActuatorEndpoint`, `TracedAspect`, `KafkaBatchLinksAspect` indirectly via `AbstractPlatformSpanBuilder`, `TracingMetricsAutoConfiguration`, `TracingCoreAutoConfiguration`) and the 3 in `-webmvc`/`-webflux` (`TraceResponseHeaderServletFilter`, `TraceResponseHeaderWebFilter`) atomically with the facade change, since they will not compile otherwise.
6. Re-run this inventory's search commands (Appendix) after the cutover commit as a zero-remaining-occurrences gate before opening the Slice 1B PR for review.

## Go / No-Go for Slice 1B

Verdict: **GO** (conditional)

Reason:
- Inventory is complete: all required symbol categories were searched repository-wide, including generated-artifact exclusion and false-positive verification for every ambiguous match.
- All findings are classified into one of the seven required categories; **zero** `REVIEW_REQUIRED` items remain after investigation.
- No unexpected old-API usage was found in modules not covered by the plan (otel-extension, platform-tracing-test, and most of e2e/bench were confirmed false positives against the OTel SDK, not `PlatformTracing`).
- One planning ambiguity remains (item 2 in Slice 1B cutover recommendations: `MeteredPlatformTracing` deletion vs. Slice 2 `TracingImplementation` timing) — this is a **sequencing decision for the architect**, not a missing/unclear inventory item, so it does not by itself justify NO-GO, but it **must be resolved explicitly before the Slice 1B PR is opened**, since it affects whether Micrometer metrics collection has a gap between Slice 1B and Slice 6.

## Appendix: raw search commands

Executed via the IDE's ripgrep-backed search tool; PowerShell-equivalent `rg` commands for reproduction on Windows:

```powershell
rg -n "currentTraceId|currentSpanId" .
rg -n "\bstartSpan\b|startRootSpan|startDetachedSpan|startChildSpan|startSpanWithLinks|\baddLink\b|\binSpan\b" .
rg -n "startHttpServer|startHttpClient|startDb\b|startRpcServer|startRpcClient|startInternal" .
rg -n "\binternalSpan\b|httpServerSpan|httpClientSpan|databaseSpan|rpcServerSpan|rpcClientSpan|kafkaProducerSpan|kafkaConsumerSpan" .
rg -n "SpanRelation|MeteredPlatformTracing|Facade\w*SpanBuilder|AbstractFacadeTypedSpanBuilder" .
rg -n "\brecordException\b" .
rg -n "CurrentTraceContext|businessSpan|ManualInstrumentation|manualInstrumentation|InstrumentedTracing|AdvancedTracing|escapeHatch|customSpan|rawSpan|\bjustification\b" -g "*.java" .
rg -n "CurrentTraceContext|businessSpan|ManualInstrumentation|manualInstrumentation|InstrumentedTracing|AdvancedTracing|escapeHatch|customSpan|rawSpan|justification" -g "*.md" .
```

Follow-up disambiguation commands (used to separate `PlatformTracing.startSpan` from OTel SDK `SpanBuilder.startSpan`):

```powershell
rg -n "\.(startSpan|startRootSpan|startDetachedSpan|startChildSpan|startSpanWithLinks|addLink|inSpan|currentTraceId|currentSpanId)\(" <module>/src/main/java
rg -n "startSpan" -B 1 <file>   # inspect receiver type (Tracer.spanBuilder(...) vs PlatformTracing instance)
```

Scope note: `build/` directories (javadoc HTML, test XML reports, binary test results) were excluded from all classification tables above; they are regenerated artifacts, not source.
