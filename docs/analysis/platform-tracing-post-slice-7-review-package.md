# TraceOperations Post-Slice 7 Review Package

**Generated:** 2026-07-07  
**Scope:** Slices 3A through 7 (after prior completion of Slices 0A/0B/1A/1B/2)  
**Purpose:** Evidence package for external adversarial review (Perplexity Sonnet / Deep Research)  
**Repository:** `E:\Platform_Traces`

---

## 1. Executive summary

TraceOperations v3 manual-tracing refactor (Slices 3A–7) is **implementation-complete** for the planned
pre-production architecture:

- **Public API** is narrowed to `traceOperations.traceContext()` + `traceOperations.manual()` only.
- **Single span-creation boundary:** all manual spans route through `TracingImplementation.startSpan(SpanSpec)`.
- **Typed builders** cover operation, HTTP, database, RPC, and Kafka consumer/producer paths.
- **Topology governance** enforces CHILD / ROOT / DETACHED semantics; ROOT+links allowed; DETACHED+links and
  CHILD+links forbidden in v1.
- **Scoped execution** applies exactly-once exception recording policy.
- **Metering** decorates `TracingImplementation` via `MeteredTracingImplementation` (not the public facade).
- **Diagnostics** expose stable `TracingDiagnosticsView` at Actuator (`manualTracing` section); internal
  `TracingState` is not serialized.
- **Observation coexistence** is proven: one observed operation does not produce two unsynchronized roots;
  manual child spans share the active trace.

**Verification status (2026-07-07):**

| Command | Result |
|---------|--------|
| `.\gradlew.bat build` | **GREEN** |
| Targeted slice test suites (see §5) | **GREEN** |

**Ready for external review:** **YES** — subject to git history attachment if/when a git remote is available
(see §10).

**Next planned slice:** Slice 8 — docs, ADR updates, compilable sample module.

---

## 2. Commits / slices included

| Slice | Goal | Status |
|-------|------|--------|
| **3A** | Operation + HTTP builder foundation | GREEN |
| **3B** | Database builder | GREEN |
| **3C-RPC** | RPC builder (client/server) | GREEN |
| **3C-Kafka** | Kafka consumer/producer builder | GREEN |
| **4** | Scoped execution / exactly-once exception policy | GREEN |
| **5A** | Topology core (ROOT/CHILD/DETACHED validation) | GREEN |
| **5B** | Links pre-start; Kafka batch ROOT+links; `RemoteContext` | GREEN |
| **6** | `MeteredTracingImplementation` SPI decorator + topology matrix | GREEN |
| **7** | Diagnostics DTO, Actuator wiring, Observation coexistence | GREEN |

Prior slices (not re-reviewed here but assumed complete): 0A, 0B, 1A, 1B, 2.

---

## 3. Files changed by slice

### Slice 3A — Operation + HTTP builders

**Production (core):**

- `platform-tracing-core/.../manual/AbstractSemanticSpanBuilder.java`
- `platform-tracing-core/.../manual/OperationSpanBuilderImpl.java`
- `platform-tracing-core/.../manual/DefaultHttpTracing.java`
- `platform-tracing-core/.../manual/DefaultManualTracing.java`
- `platform-tracing-core/.../manual/DefaultTransportTracing.java` (stub/entry)

**Tests:**

- `platform-tracing-core/.../manual/OperationSpanBuilderTest.java`
- `platform-tracing-core/.../manual/HttpSpanBuilderTest.java`

### Slice 3B — Database builder

**Production:**

- `platform-tracing-core/.../manual/DatabaseSpanBuilderImpl.java`

**Tests:**

- `platform-tracing-core/.../manual/DatabaseSpanBuilderTest.java`

### Slice 3C-RPC — RPC builder

**Production:**

- `platform-tracing-core/.../manual/DefaultRpcTracing.java`

**Tests:**

- `platform-tracing-core/.../manual/RpcSpanBuilderTest.java`

### Slice 3C-Kafka — Kafka builder

**Production:**

- `platform-tracing-core/.../manual/DefaultKafkaTracing.java`

**Tests:**

- `platform-tracing-core/.../manual/KafkaSpanBuilderTest.java`

### Slice 4 — Scoped execution

**Production:**

- `platform-tracing-core/.../manual/ScopedExecution.java`
- `platform-tracing-core/.../manual/SpecifiedSpanImpl.java`
- `platform-tracing-core/.../manual/SpanHandleImpl.java`

**Tests:**

- `platform-tracing-core/.../manual/ScopedExecutionTest.java`
- `platform-tracing-core/.../manual/SpecifiedSpanTest.java`

### Slice 5A — Topology core

**Production:**

- `platform-tracing-core/.../manual/AbstractSemanticSpanBuilder.java` (order-independent `linkedTo()` / `root()`)
- `platform-tracing-api/.../span/spec/SpanOptions.java` (`validateTopologyLinks`)

**Tests:**

- `platform-tracing-core/.../manual/SpanOptionsTopologyTest.java`

### Slice 5B — Links + Kafka batch ROOT+links

**Production:**

- `platform-tracing-api/.../span/RemoteContext.java`
- `platform-tracing-api/.../span/spec/SpanSpecBuilder.java` (`fromRemoteContext`)
- `platform-tracing-api/.../span/spec/DefaultSpanSpecBuilder.java`
- `platform-tracing-core/.../impl/DefaultTracingImplementation.java` (link application)

**Tests:**

- `platform-tracing-api/.../span/RemoteContextTest.java`
- `platform-tracing-core/.../manual/KafkaConsumerBatchLinksTest.java`

**Deferred:** none for aspect boundary — `KafkaBatchLinksAspect` migrated in Slice 7 remediation B03.

**TracedAspect:** confirmed compliant — production code uses `traceOperations.manual().operation(...).start()`; no change required.

### Slice 6 — MeteredTracingImplementation

**Production:**

- `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredTracingImplementation.java`
- `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredSpanHandle.java`
- `platform-tracing-spring-boot-autoconfigure/.../TracingCoreAutoConfiguration.java` (metered wrap when ENABLED)
- `platform-tracing-core/.../impl/DelegatingTracingImplementation.java`

**Tests:**

- `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredTopologyMatrixTest.java`
- `platform-tracing-spring-boot-autoconfigure/.../metrics/MeteredMetricsCountTest.java`
- `platform-tracing-spring-boot-autoconfigure/.../BeanTopologyTest.java` (updated)

**Docs:**

- `docs/known-issues/R01.md` (Slice 6 structural fix)

### Slice 7 — Diagnostics + Observation coexistence

**Production:**

- `platform-tracing-spring-boot-autoconfigure/.../diagnostics/TracingDiagnosticsView.java`
- `platform-tracing-spring-boot-autoconfigure/.../diagnostics/TracingDiagnosticsMapper.java`
- `platform-tracing-spring-boot-autoconfigure/.../diagnostics/ManualTracingDiagnostics.java`
- `platform-tracing-spring-boot-autoconfigure/.../TracingCoreAutoConfiguration.java` (bean)
- `platform-tracing-spring-boot-autoconfigure/.../TracingActuatorAutoConfiguration.java`
- `platform-tracing-spring-boot-autoconfigure/.../actuator/TracingActuatorEndpoint.java` (`manualTracing` section)

**Tests:**

- `platform-tracing-spring-boot-autoconfigure/.../diagnostics/DiagnosticsBoundaryTest.java`
- `platform-tracing-spring-boot-autoconfigure/.../diagnostics/TracingDiagnosticsViewJsonContractTest.java`
- `platform-tracing-spring-boot-autoconfigure/.../diagnostics/ObservationCoexistenceTest.java`
- `platform-tracing-spring-boot-autoconfigure/.../diagnostics/SpringBootContextMatrixTest.java`
- Updated: `TracingActuatorEndpointTest.java`, `TracingActuatorEndpointProcessorErrorsTest.java`

**Docs:**

- `docs/known-issues/R01.md` (Observation coexistence closed)

---

## 4. Key architectural invariants and where they are tested

| Invariant | Proof |
|-----------|-------|
| Public facade is `traceContext()` + `manual()` only | `TraceOperations.java`; `V3ManualApiArchTest`; no v1 `startSpan` on public API |
| Single creation boundary: `TracingImplementation.startSpan(SpanSpec)` | `TracingImplementationRoutingTest`; `TracingImplementationArchTest` |
| Public builders do not call OTel `Tracer` directly | ArchUnit in core; builder tests route through SPI |
| CHILD default under active context | `SpanOptionsTopologyTest`, `OperationSpanBuilderTest` |
| ROOT / DETACHED topology | `SpanOptionsTopologyTest`, `MeteredTopologyMatrixTest` |
| ROOT+links allowed; DETACHED+links / CHILD+links forbidden | `SpanOptionsTopologyTest`, `KafkaConsumerBatchLinksTest`, `MeteredTopologyMatrixTest` |
| Kafka batch ROOT+links | `KafkaConsumerBatchLinksTest`, `MeteredTopologyMatrixTest` |
| Links pre-start only (`SpanHandle` has no `addLink`) | `KafkaConsumerBatchLinksTest` |
| Exactly-once exception recording in scoped execution | `ScopedExecutionTest` |
| Metering on SPI, not public facade | `BeanTopologyTest`, `MeteredTopologyMatrixTest` |
| Metered decorator preserves topology | `MeteredTopologyMatrixTest` (parentSpanId, links, fail-fast) |
| Metric counts separate from topology assertions | `MeteredMetricsCountTest` (R12) |
| Exactly one `TracingImplementation` bean chain | `BeanTopologyTest` |
| Diagnostics DTO stable; no raw `TracingState` in JSON | `DiagnosticsBoundaryTest`, `TracingDiagnosticsViewJsonContractTest` |
| Internal TEST mode → public `"UNKNOWN"` | `TracingDiagnosticsViewJsonContractTest`, `DiagnosticsBoundaryTest` |
| Observation + manual: no duplicate unsynchronized roots | `ObservationCoexistenceTest` |
| Disabled platform tracing does not break Observation | `ObservationCoexistenceTest` |
| Intentional `manual().root()` inside Observation = separate trace | `ObservationCoexistenceTest` |

---

## 5. Verification command log

Captured 2026-07-07 on Windows (`E:\Platform_Traces`).

### Full build

```
.\gradlew.bat build
```

**Result:** BUILD SUCCESSFUL (106 tasks; collector-config Docker validation skipped offline — expected).

### Targeted slice tests

```
.\gradlew.bat :platform-tracing-core:test ^
  --tests "*OperationSpanBuilder*" ^
  --tests "*DatabaseSpanBuilder*" ^
  --tests "*RpcSpanBuilder*" ^
  --tests "*KafkaSpanBuilder*" ^
  --tests "*ScopedExecution*" ^
  --tests "*SpanOptions*" ^
  --tests "*KafkaConsumer*"

.\gradlew.bat :platform-tracing-api:test --tests "*RemoteContext*"

.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test ^
  --tests "*MeteredTopologyMatrixTest*" ^
  --tests "*MeteredMetricsCountTest*" ^
  --tests "*MetricsCount*" ^
  --tests "*DiagnosticsBoundaryTest*" ^
  --tests "*ObservationCoexistenceTest*"
```

**Result:** BUILD SUCCESSFUL (all targeted suites GREEN).

### Slice 7 gate (from plan)

```
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test ^
  --tests "*DiagnosticsBoundaryTest*" ^
  --tests "*ObservationCoexistenceTest*"
```

**Result:** GREEN (included in run above).

---

## 6. Known limitations / deferred items

| Item | Status | Notes |
|------|--------|-------|
| `KafkaBatchLinksAspect` migration to v3 facade | **CLOSED (B03)** | Migrated to manual batch API; `KafkaBatchAspectMigrationTest` |
| `TracedAspect` full v3 rewiring | **CLOSED (verified compliant)** | Already uses `manual().operation(...).start()`; no production change |
| Agent-on primary e2e for Observation coexistence | **Deferred** | `ObservationCoexistenceTest` uses bridge-otel path; ADR OQ-2 recommends Agent-on as production gate |
| Fleet-default `suppress-micrometer-tracing` in Helm | **Open** | ADR OQ-1 — deployment policy |
| Slice 8 docs / sample module | **Not started** | `platform-tracing-samples:compileJava` gate pending |
| Git commit history in workspace | **Unavailable** | `E:\Platform_Traces` is not a git repository in current environment; attach diff from CI or canonical remote |
| E2E tests (`-PrunE2e`) | **Not run** | Requires Docker/Testcontainers; not part of default build |

---

## 7. R01 final status

### Structurally fixed

1. **Public-facade decorator removed** — `MeteredPlatformTracing` deleted in Slice 1B; not restored.
2. **Narrow facade** — no behavioral interface defaults on span creation (`TraceOperations` = 2 methods).
3. **SPI metering** — `MeteredTracingImplementation` fully delegates `startSpan`, `state`, `recordException`.
4. **Topology preserved through metered chain** — ROOT, DETACHED, ROOT+links proven in `MeteredTopologyMatrixTest`.
5. **Observation coexistence** — no competing unsynchronized roots (`ObservationCoexistenceTest`).

### Tests that prove it

- `BeanTopologyTest` — single SPI chain; metered wrap; no public-facade decorator bean
- `TracingImplementationRoutingTest` — single creation boundary
- `MeteredTopologyMatrixTest` — topology + links through decorator
- `MeteredMetricsCountTest` — metrics on SPI without masking topology
- `ObservationCoexistenceTest` — Micrometer Observation coexistence

### Remaining open (non-R01)

- Production Agent-on duplicate-span e2e sign-off (existing `DuplicateHttpSpanAgentSmokeTest` path)
- Operational policy for `suppress-micrometer-tracing` default (ADR OQ-1)
- Slice 8 adoption docs for consuming teams

**R01 verdict for review:** **Structurally addressed** — durable proof gates GREEN; historical v1 defect class
should not recur on v3 API if invariants hold.

---

## 8. Grep gates

Run against production `src/main/**/*.java` unless noted.

| Gate | Command / check | Result |
|------|-----------------|--------|
| No `MeteredPlatformTracing` class restored | `rg 'class MeteredPlatformTracing' --glob '**/src/main/**'` | **PASS** (0 matches) |
| No `SpanRelation` enum | `rg 'enum SpanRelation\|SpanRelation\.' --glob '**/src/main/**'` | **PASS** (0 matches) |
| No `Facade*` / `AbstractFacadeTypedSpanBuilder` | `rg 'Facade\w*SpanBuilder\|AbstractFacadeTypedSpanBuilder' --glob '**/src/main/**'` | **PASS** (0 matches) |
| No v1 public `TraceOperations.startSpan` | `TraceOperations.java` has only `traceContext()` + `manual()` | **PASS** |
| Diagnostics: no raw `TracingState` in Actuator JSON | `TracingActuatorEndpoint` uses `ManualTracingDiagnostics.toActuatorMap()` | **PASS** |
| Diagnostics mapper internal-only | `TracingDiagnosticsMapper` maps to `TracingDiagnosticsView`; TEST→UNKNOWN | **PASS** |
| Public OTel SDK leak (manual API) | `platform-tracing-api` uses `AttributeKey` only (pre-existing semconv); no `Tracer`/`Span` in manual builders | **PASS** (note: legacy semconv keys remain — pre-Slice-3) |

**Comments/docs** may still mention `MeteredPlatformTracing` historically (`R01.md`, javadoc) — expected.

---

## 9. Open questions for external reviewer

1. **Agent-on vs bridge-otel:** Is `ObservationCoexistenceTest` (bridge-otel) sufficient pre-production, or must Agent-on e2e gate merge before sign-off? (ADR OQ-2)
2. **TracedAspect / KafkaBatchLinksAspect:** Are deferred aspect migrations acceptable for pre-production, or blocking?
3. **Diagnostics DTO evolution:** Is closed set `{ENABLED, DISABLED_BY_CONFIGURATION, UNAVAILABLE, NOOP, UNKNOWN}` sufficient for SRE tooling?
4. **AttributeKey in public API:** Pre-existing OTel `AttributeKey` in transport builders — acceptable or should Slice 8 remove/replace?
5. **False-green risk:** Do any v1 characterization tests (`DefaultTraceOperationsBaselineTest`, etc.) still give misleading GREEN signal post-cutover?
6. **Slice 8 priority:** Which ADR topics from plan §Slice 8 are merge-blocking vs nice-to-have?

---

## 10. Raw diff summary

**Note:** `E:\Platform_Traces` is **not a git repository** in the current workspace. Commands below failed locally:

```
git status / git log / git diff → fatal: not a git repository
```

**For external review, attach from canonical remote:**

```bash
git log --oneline --since="2026-01-01"   # adjust range to slice branch
git diff --stat <base-branch>...HEAD
git diff --name-status <base-branch>...HEAD
```

**Approximate change surface (Slices 3A–7, from file inventory):**

| Module | New/changed areas |
|--------|-------------------|
| `platform-tracing-api` | `RemoteContext`, span spec builders |
| `platform-tracing-core` | `manual/*` builders, scoped execution, topology |
| `platform-tracing-spring-boot-autoconfigure` | `metrics/Metered*`, `diagnostics/*`, Actuator endpoint |
| `docs` | `R01.md`, this review package |

---

## 11. Reviewer checklist (from plan §12)

Use with `docs/analysis/platform-tracing-refactoring-plan.md` v3.4.2 and
`docs/decisions/ADR-platform-tracing-micrometer-observation-boundary.md`.

- [ ] Implementation matches v3.4.2 plan and accepted ADR (Option C hybrid model)
- [ ] No hidden v1 API restoration
- [ ] `TracingImplementation.startSpan(SpanSpec)` remains sole manual creation boundary
- [ ] Topology/link matrix correct (including Kafka batch ROOT+links)
- [ ] Scoped execution exactly-once exception policy
- [ ] `MeteredTracingImplementation` delegate-only; no R01 recurrence
- [ ] Metrics tests do not mask topology defects
- [ ] Diagnostics DTO does not leak internal state or OTel SDK types
- [ ] Observation coexistence: no duplicate unsynchronized roots
- [ ] Tests are meaningful (not false-green)

---

## 12. Scope confirmation

| In scope (this package) | Out of scope |
|-------------------------|--------------|
| Slices 3A, 3B, 3C-RPC, 3C-Kafka, 4, 5A, 5B, 6, 7 | Slice 8 docs/samples |
| Structural R01 + R24 (Observation) proof | Production rollout / Helm policy |
| Default Gradle build + named test gates | E2E with `-PrunE2e` unless explicitly run |
| Grep gates on production sources | Git history (attach separately) |

**Report path:** `docs/analysis/platform-tracing-post-slice-7-review-package.md`  
**Verification:** GREEN  
**Ready for Perplexity / external adversarial review:** **YES**
