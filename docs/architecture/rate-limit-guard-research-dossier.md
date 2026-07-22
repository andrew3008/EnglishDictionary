# Rate-Limit Guard Research Dossier

> **Topic:** Should Platform Tracing implement a Rate-Limit Guard for traces/sec?
> **Audience:** Perplexity / architecture committee / LLM scoring pass
> **Repository snapshot:** `spring-boot-platform-tracing` (`e:\Platform_Traces`)
> **Date:** 2026-06-16
> **Scope:** Evidence collection only — **no implementation decision**, no code changes

---

## 1. Executive Summary

**Current need status:** **NOT PROVEN IN REPOSITORY.** No documented incident, no contractual SLO for traces/sec per service, and no load-test artifact showing that existing head-ratio + collector tail sampling + export/collector backpressure fail to protect backend during burst.

**Current architecture fit:** **GOOD IF NEEDED.** Platform Tracing already has a pure-Java `SamplingPolicyEngine` with a fixed 7-rule chain (`platform-tracing-otel`) delegated by `CompositeSampler` (`platform-tracing-otel-javaagent-extension`). A rate-limit guard could be modeled as an 8th `SamplingPolicyRule`, but today all rules are **stateless** and the engine instance in `CompositeSampler` is **`final` and built once at startup** — introducing a stateful token-bucket rule or runtime limit changes requires explicit design work on engine lifecycle.

**Biggest overengineering risks:**
1. Duplicating protection already provided by ratio sampling, hard-drop paths, collector tail sampling, collector `memory_limiter`, and agent export queue (`PlatformDropOldestExportSpanProcessor`).
2. Adding a **stateful hot-path rule** while M5 macro perf budgets are already **PENDING/FAIL** (+48% CPU documented in migration plan).
3. Expanding dual-channel runtime config / JMX / metrics surface without proven operational gap.
4. Confusing **per-process traces/sec cap** with **cluster-wide** backend protection.

**What must be proven before implementation:**
- At least one of: documented incident, load test proving collector inlet > budget under realistic burst, or contractual SLO `N traces/sec per service`.
- Explicit committee decision on chain position, per-process vs cluster scope, and engine/state lifecycle.
- JMH evidence that disabled rate-limit adds negligible hot-path cost (no established +5% budget in repo — see §8).

**Central anti-overengineering question:**

```text
Does Rate Limit solve a proven operational gap, or is it a preemptive feature
that duplicates existing sampling/backpressure controls?
```

Repository answer today: **likely preemptive** — evidence missing.

---

## 2. Gate Decision Evidence

### 2.1 What evidence exists in the repository

| Evidence type | Finding | Source |
|---|---|---|
| Existing head sampling | Default ratio `0.1` (Spring + agent extension defaults aligned) | `TracingProperties.Sampling.ratio=0.1`; `ExtensionDefaults.DEFAULT_SAMPLING_RATIO=0.1` |
| Runtime ratio mutation | JMX `setSamplingRatio`, atomic `updateSamplingPolicy`, Spring `RuntimeConfigApplier` | `PlatformTracingControlMBean`, `SamplingControlClient`, `RuntimeConfigApplier.applySampling()` |
| Hard-drop hot paths | Default drop paths for actuator endpoints | `TracingProperties.Sampling.dropPaths` |
| Collector tail sampling | ERROR/slow/priority policies; `expected_new_traces_per_sec: 1000`; trace size cap | `otel-collector-gateway-tail-sampling.yaml` |
| Collector memory protection | `memory_limiter` first in gateway pipeline | same YAML |
| Agent export backpressure | Drop-oldest queue, overflow counters, JMX export metrics | `PlatformDropOldestExportSpanProcessor`, `SamplingControlClient.getExportMetrics()` |
| Macro perf concern (agent overhead) | M5 documented FAIL baseline (+48% CPU, +25% RSS) in migration docs | `platform-tracing-preservation-first-migration-plan.md` |
| External reference pattern available locally | OTel Jaeger `RateLimitingSampler` + SDK internal `RateLimiter` (conceptual only) | `E:\Platform_Traces_Examples\src\_Dynatrace\opentelemetry-java\sdk-extensions\jaeger-remote-sampler\` |
| Draft architecture plan | Proposes optional 8th rule + gate criteria | `rate-limit_guard_arch_37ea302d.plan.md` (**Plan claim**, not repo truth) |

**No repository evidence found for:**
- Documented production incident where ratio + tail sampling failed to protect backend
- Load test artifact: `ratio=0.1` at X rps → collector inlet Y spans/sec > budget Z
- Contractual SLO / requirement text: `N traces/sec per service`
- Property, class, or test named `rate-limit`, `rateLimit`, `RateLimitPolicyRule`, `maxTracesPerSecond`

Evidence: repo-wide grep for `traces/sec`, `rate-limit-per-second`, `RateLimitPolicyRule` → **NOT FOUND IN REPOSITORY**.

### 2.2 What evidence is missing

1. **Operational gap narrative** — concrete burst scenario where all existing layers fail simultaneously.
2. **Quantified backend budget** — target traces/sec (per service, per pod, or cluster).
3. **Load/perf reproduction** — perf-tests M0–M10 do not define a rate-limit gate scenario.
4. **SRE/compliance requirement** — no traces/sec SLO in docs (distinct from separate ≤200 spans/trace requirement discussed elsewhere).
5. **Committee sign-off** on whether reactive JMX ratio reduction is insufficient vs automatic absolute cap.

### 2.3 Gate questions for architecture committee

1. **Need:** What incident or load test proves ratio + tail sampling + collector memory limiter + export queue are insufficient?
2. **Semantics:** Is an **absolute traces/sec cap** required, or is lowering `platform.tracing.sampling.ratio` via JMX/RefreshScope acceptable burst response?
3. **Scope:** Is **per-process** cap sufficient, or is **cluster-wide** coordination required?
4. **Chain position:** Should rate-limit apply before route/default ratio, after parent decision, or as last resort?
5. **Force/QA bypass:** Should `ForceHeader` / `QaTrace` bypass rate-limit (current chain gives them higher priority)?
6. **Engine lifecycle:** Accept stateful rule object in fixed engine, or require engine rebuild on limit change?
7. **Observability:** Is extending fixed Micrometer reason list acceptable, or is existing JMX decision counter enough?
8. **Perf budget:** What hot-path regression budget applies given M5 PENDING status?

### 2.4 Preliminary gate verdict

**GATE_NOT_READY**

Rationale: committee rule ("implement only if truly needed") requires incident, load test, or contractual SLO — **none found in repository**. Draft plan correctly defines gate criteria but provides no satisfying evidence.

---

## 3. Current Sampling Architecture

### 3.1 Rule chain inventory

Production chain (7 rules), built by `SamplingPolicyEngine.productionEngine()`:

| # | Class | `ruleName()` constant |
|---|---|---|
| 1 | `KillSwitchPolicyRule` | `kill_switch` |
| 2 | `HardDropPolicyRule` | `hard_drop` |
| 3 | `ForceHeaderPolicyRule` | `force_header` |
| 4 | `QaTracePolicyRule` | `qa_trace` |
| 5 | `ParentSampledPolicyRule` | `parent_decision` |
| 6 | `RouteRatioPolicyRule` | `route_ratio` |
| 7 | `DefaultRatioPolicyRule` | `default_ratio` |

Evidence: `platform-tracing-otel/.../SamplingPolicyEngine.java`, method `productionEngine()`; verified by `SamplingPolicyEngineTest.productionEngine_matchesCompositeSamplerRuleOrder()`.

Foundation subset (2 rules): `KillSwitchPolicyRule`, `HardDropPolicyRule` via `foundationEngine()`.

### 3.2 Rule chain build location

| Location | Behavior |
|---|---|
| `SamplingPolicyEngine.productionEngine()` | Static factory; constructs rule array |
| `CompositeSampler` constructor | Calls `SamplingPolicyEngine.productionEngine()` once |
| `SamplerState.policySnapshot()` | Rebuilt on each `SamplerState` construction from runtime config fields |

Evidence: `CompositeSampler.java` lines 35–37: `this.policyEngine = SamplingPolicyEngine.productionEngine();`

**Inference:** Runtime JMX updates replace `SamplerState` snapshots via `SamplerStateHolder` CAS, but **do not rebuild** `SamplingPolicyEngine` or rule instances.

### 3.3 Current rule responsibilities

| Rule | Reads from snapshot | Decision behavior |
|---|---|---|
| `KillSwitchPolicyRule` | `enabled` | DROP if disabled |
| `HardDropPolicyRule` | `droppedRoutes` | DROP if `url.path` prefix match |
| `ForceHeaderPolicyRule` | `forceRecordValues` | RECORD_AND_SAMPLE if header value match |
| `QaTracePolicyRule` | *(request.qaTrace)* | RECORD_AND_SAMPLE if QA flag |
| `ParentSampledPolicyRule` | *(request.parentContextState)* | SAMPLE/DROP/pass based on parent |
| `RouteRatioPolicyRule` | `routeRatios[]` | traceId ratio per longest prefix |
| `DefaultRatioPolicyRule` | `defaultRatio` | terminal traceId ratio |

All current rules are **stateless** — no instance fields mutated across `evaluate()` calls.

Evidence: e.g. `KillSwitchPolicyRule.java` — static DROP decision, reads only `snapshot.enabled()`.

Characterization matrix: `docs/architecture/platform-tracing-sampling-characterization.md`.

### 3.4 Existing DROP reasons

**Core enum** (`SamplingPolicyReason`): `KILL_SWITCH`, `HARD_DROP`, `FORCE_HEADER` *(sample)*, `QA_TRACE` *(sample)*, `PARENT_DECISION`/`PARENT_DROP`, `ROUTE_RATIO`/`ROUTE_RATIO_DROP`, `DEFAULT_RATIO`/`DEFAULT_RATIO_DROP`, `NO_MATCH`.

**Exported span attribute codes** (`PlatformSamplingReasons`): `force_header`, `qa_trace`, `parent_sampled`, `route_ratio`, `global_ratio` (sampled); `parent_drop`, `route_ratio_drop`, `global_ratio_drop`, `drop_path` (dropped); `kill_switch`, `fallback_drop` (metric-only on span).

Evidence: `SamplingPolicyReason.java`, `PlatformSamplingReasons.java`.

**Rule API supports DROP with reason:** `SamplingPolicyDecision.drop(SamplingPolicyReason, winningRule)`.

Evidence: `SamplingPolicyDecision.java`, method `drop(...)`.

Pass-through: rule returns `null` → engine continues to next rule.

Evidence: `SamplingPolicyEngine.evaluate()` — `if (decision != null) return decision;`

### 3.5 Rate Limit insertion points

| Insertion point | Pros | Cons | Semantic meaning |
|---|---|---|---|
| **After `ParentSampledPolicyRule`, before `RouteRatioPolicyRule`** | Respects upstream parent decision; caps only "new head" decisions before per-route tuning | Still runs after Force/QA (forced traces not capped); **Plan claim: preferred** | Global per-process cap on head decisions that reached ratio stage |
| **Before `ForceHeaderPolicyRule`** | Earliest volume cut | Breaks operational force/QA guarantees; likely unacceptable | Aggressive overload shedding |
| **After `DefaultRatioPolicyRule` (last resort)** | Only caps traces that would otherwise be sampled by ratio rules | Too late — ratio work already done; does not reduce rule evaluation cost | "Final absolute cap" on sampled stream |
| **Before `ParentSampledPolicyRule`** | Reduces work before parent logic | Can drop child spans while parent sampled — breaks trace coherence | Generally incorrect for distributed tracing |
| **Not in chain (defer / ops-only)** | Zero hot-path cost; use existing JMX ratio + collector controls | No automatic absolute cap | **Lowest overengineering risk** |

**Natural insertion point (if implemented):** after `ParentSampledPolicyRule`, before `RouteRatioPolicyRule` — matches draft plan and preserves parent/force/QA semantics.

**Stateful rule support today:** **NO.** Current `SamplingPolicyRule` contract is stateless; engine holds fixed rule instances. A token-bucket rate limiter requires either:
- mutable state inside a rule instance (new pattern), or
- engine rebuild when limit changes (**not supported today**).

Evidence: `CompositeSampler.policyEngine` is `private final`; no rebuild path in `SamplerStateHolder`.

---

## 4. Current Runtime Update Architecture

### 4.1 SamplerState / SamplerStateHolder

- `SamplerState` — immutable snapshot: `enabled`, `droppedRoutes`, `forceRecordValues`, `routeRatios`, `defaultRatio`, `version`, `source`, compiled `SamplingPolicySnapshot`.
- `SamplerStateHolder` — wraps `DomainConfigHolder<SamplerState>`; CAS + last-known-good.
- Atomic full-policy publish: `tryApplyPolicyUpdate(enabled, defaultRatio, droppedRoutes, forceRecordValues, routeRatioPrefixes, routeRatioValues, source)`.

Evidence: `SamplerState.java`, `SamplerStateHolder.java`, `SamplerPolicyUpdate.java`.

Validation limits: `MAX_DROP_PATHS=100`, `MAX_FORCE_VALUES=50`, ratio ∈ [0,1].

### 4.2 SamplingPolicyEngine lifecycle

| Property | Current behavior |
|---|---|
| Construction | Once per `CompositeSampler` |
| Mutability | `private final SamplingPolicyEngine policyEngine` |
| Rebuild on JMX update | **NO** — only `SamplerState` / `policySnapshot` changes |
| Rule instances | Fixed at engine creation; shared across all threads |

Evidence: `CompositeSampler.java` fields and constructor.

**Plan claim:** engine rebuild or mutable engine required for runtime limit changes — **VERIFIED against repository**.

### 4.3 CompositeSampler hot path

```text
shouldSample()
  → configHolder.current()                    // lock-free read
  → SamplingPolicyOtelAdapter.toRequest(...)
  → policyEngine.evaluate(request, state.policySnapshot())
  → SamplingPolicyOtelAdapter.toSamplingResult(decision)
  → recordDecision(decision, metricRuleName)  // ConcurrentHashMap + LongAdder
```

Evidence: `CompositeSampler.shouldSample()`, `SamplingPolicyOtelAdapter`.

Called on **every span start** — highest-frequency platform code path (documented in `CompositeSamplerBenchmark` javadoc).

### 4.4 JMX update path

```text
RuntimeConfigApplier.applySampling()
  → SamplingControlClient.updateSamplingPolicy(SamplingRuntimeConfig)
  → MBean updateSamplingPolicy(enabled, ratio, dropPaths, forceValues, routeRatios, source)
  → SamplerStateHolder.tryApplyPolicyUpdate(...)
  → new SamplerState (version++)
```

Existing sampling MBean operations: `get/setSamplingRatio`, `setDropPathPrefixes`, `setForceRecordValues`, `setSamplerEnabled`, `setRouteRatios`, `updateSamplingPolicy` (2 overloads), decision counters, config version/source.

Evidence: `PlatformTracingControlMBean.java`, `SamplingControlClient.java`.

**No** `get/setMaxTracesPerSecond` today.

### 4.5 Engine rebuild feasibility

| Approach | Feasibility today | Notes |
|---|---|---|
| Add field to `SamplingPolicySnapshot` only | Insufficient alone | Rules are stateless; token bucket needs mutable credits |
| Stateful `RateLimitPolicyRule` in fixed engine | **Possible with design change** | Rule holds `RateLimiter`; snapshot carries limit value; limit change updates rule state or recreates limiter |
| Rebuild `SamplingPolicyEngine` on config change | **Not implemented** | Would require non-final engine reference in `CompositeSampler` |
| Separate OTel `RateLimitingSampler` wrapper | **Rejected by ADR pattern** | Would bypass rule chain; incompatible with `CompositeSampler` model |

---

## 5. Current Spring / Dual-Channel Property Architecture

### 5.1 Existing sampling properties

`TracingProperties.Sampling` (runtime-mutable via atomic `updateSamplingPolicy`):

| Property | Type | Default | Runtime mutable |
|---|---|---|---|
| `enabled` | boolean | `true` | Yes |
| `ratio` | double | `0.1` | Yes |
| `routeRatios` | Map | empty | Yes |
| `forceRecordHeader` | String | `X-Trace-On` | Startup only |
| `forceRecordHeaderValues` | List | `["on"]` | Yes |
| `qaForceHeader` | String | `X-QA-Trace` | Startup only |
| `dropPaths` | List | actuator paths | Yes |

Evidence: `TracingProperties.java` lines 141–185; `SamplingRuntimeConfig.java` schema v1 list.

Agent mirror keys: `ExtensionPropertyNames.SAMPLING_*` (`platform.tracing.sampling.ratio`, `.enabled`, `.route-ratios`, etc.).

### 5.2 RuntimeConfigApplier path

- `applyAll(TracingProperties)` → per-domain isolated try/catch
- Sampling: `SamplingRuntimeConfig.from(properties.getSampling())` → `client.updateSamplingPolicy(config)`
- Metric: `platform.tracing.config.apply.result{domain,result}`

Evidence: `RuntimeConfigApplier.java`.

Triggered on `RefreshScopeRefreshedEvent` (no stale cache in applier).

Evidence: `TracingRefreshScopeAutoConfiguration.java`; `runtime-policy-control-architecture.md`.

### 5.3 Proposed property placement

**Plan claim:** `platform.tracing.sampling.rate-limit-per-second` (null/absent = disabled).

Fit:
- Same namespace as other sampling policy fields
- Would require extending `TracingProperties.Sampling`, `SamplingRuntimeConfig`, `SamplerState`, `SamplerPolicyUpdate`, `ExtensionPropertyNames`, JMX `updateSamplingPolicy` payload, and `SamplingControlClient.invokeUpdateSamplingPolicy` signature

**NOT present in repository today.**

### 5.4 Spring vs agent ownership

Per ADR/runtime docs: **agent-side holder is source of truth**; Spring is input/reconciliation layer only.

Evidence: `TracingProperties.Sampling` javadoc; `SamplingRuntimeConfig` javadoc; `runtime-policy-control-architecture.md`.

Dual-channel alignment tested: `SharedDefaultsAlignmentTest` (BSP queue defaults); drift via `DualChannelDriftDiagnostics`.

**Default ratio nuance:** Spring default `0.1` matches `ExtensionDefaults.DEFAULT_SAMPLING_RATIO`. But `PlatformSamplerBuilder` uses `defaultRatio = 1.0` when `SAMPLING_RATIO` property string is absent, then reads double default — startup-window alignment depends on agent property injection.

Evidence: `PlatformSamplerBuilder.build()` lines 50–55; `ExtensionDefaults.DEFAULT_SAMPLING_RATIO=0.1`.

### 5.5 Risks of adding one more runtime-mutable property

1. Expands atomic JMX payload — all callers of `updateSamplingPolicy` must stay in sync.
2. Increases dual-channel drift surface (Spring vs agent startup window).
3. Another field in `SamplerPolicyUpdate.validateDomain`.
4. Characterization tests (`SamplingDecisionMatrixCharacterizationTest`) assume 7-rule order — insertion changes golden master.
5. Scope creep toward full reconciler / desired-state layer (not in codebase).

Evidence: `ADR-runtime-config-policy-vs-topology.md` — topology (BSP queue size) is startup-only; rate-limit would be **policy** (runtime-mutable) if added.

---

## 6. Existing Protection Mechanisms

| Mechanism | Layer | What it protects | What it does not protect | Evidence |
|---|---|---|---|---|
| `DefaultRatioPolicyRule` (ratio=0.1) | agent / core | Probabilistic head sampling — ~10% of root traces | Absolute cap at burst (10k rps → ~1k sampled/sec); nested spans in already-sampled traces | `DefaultRatioPolicyRule.java`, `TracingProperties.Sampling.ratio` |
| `RouteRatioPolicyRule` | agent / core | Per-path ratio tuning | Global burst; paths without prefix match | `RouteRatioPolicyRule.java` |
| `HardDropPolicyRule` | agent / core | Known noisy paths (actuator) | Dynamic burst on business endpoints | `HardDropPolicyRule.java`, default drop paths |
| `KillSwitchPolicyRule` | agent / core | Emergency off | Partial throttling | `KillSwitchPolicyRule.java` |
| `ForceHeader` / `QaTrace` rules | agent / core | Guaranteed sample for ops/QA | Can increase volume when used | characterization matrix |
| `tail_sampling` (gateway) | collector | ERROR/slow/priority retention; reduces export volume | Spans already exported from agent; agent-side CPU/memory | `otel-collector-gateway-tail-sampling.yaml` |
| `memory_limiter` | collector | Collector OOM / backpressure | Agent-side span creation rate | same YAML |
| `maximum_trace_size_bytes` | collector | Oversized trace blobs in tail sampler | Per-second trace count at agent | same YAML (`maximum_trace_size_bytes`) |
| `PlatformDropOldestExportSpanProcessor` | agent / export | Export queue overflow — drop oldest | Sampling decision rate (spans still created) | `PlatformDropOldestExportSpanProcessor.java` |
| BSP queue defaults (2048) | agent / export | Export batching baseline | Head sampling rate | `OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE=2048` |
| Runtime `setSamplingRatio` / RefreshScope | Spring → JMX → agent | Manual/automated ratio reduction | Automatic token-bucket; requires operator/automation | `SamplingControlClient.setRatio()`, `RuntimeConfigApplier` |
| `SafeSampler` fallback | agent | Degraded DROP on sampler exceptions | Volume control | `SafeSampler.java` |

**Failure/burst scenario potentially uncovered (Inference):**

At extreme RPS with moderate ratio, **sampled traces/sec = RPS × ratio** can still exceed backend budget (e.g. 10k × 0.1 = 1k traces/sec). Rate-limit would cap absolute sampled/sec **per process**. Collector memory limiter protects collector, not agent CPU or OTLP ingress cost from already-sampled traffic.

**Absolute cap vs ratio:** Materially different semantics — ratio scales with load; absolute cap does not.

**Per-process vs cluster:** Repository implements **per-process** sampling only. Cluster-wide cap would require external coordination — **NOT FOUND IN REPOSITORY**.

---

## 7. Observability and Metrics Impact

### 7.1 Existing sampler metrics

**Agent-side counters:** `CompositeSampler.decisionCounters` keyed `"DECISION:winningRule"` (e.g. `DROP:hard_drop`, `RECORD_AND_SAMPLE:global_ratio`).

Evidence: `CompositeSampler.recordDecision()`, `getDecisionCounts()`.

**Micrometer binder:** `PlatformTracingSamplerMetricsBinder` registers **fixed lists** of reasons from `PlatformSamplingReasons` — predeclared at bind time.

Evidence: `PlatformTracingSamplerMetricsBinder.java` — arrays `sampledReasons`, `droppedReasons`.

Metric name: `platform.tracing.sampler.decisions{decision,reason}`.

### 7.2 Existing config/apply metrics

| Metric | Meaning |
|---|---|
| `platform.tracing.config.apply.result{domain,result}` | Spring-side JMX push success/failure |
| `platform.tracing.config.updates{result=applied\|rejected}` | Agent config reload counters via JMX |
| `platform.tracing.config.sampling_version` | Active sampler snapshot version |
| `platform.tracing.sampling.invalid_config` | Rejected sampler config updates |

Evidence: `RuntimeConfigApplier`, `PlatformTracingConfigMetricsBinder`.

### 7.3 Would RATE_LIMIT reason be observable automatically?

**NO** — not with current Micrometer binder.

Adding `SamplingPolicyReason.RATE_LIMIT` would increment `CompositeSampler` internal counter keyed by `winningRule`, but **`PlatformTracingSamplerMetricsBinder` would not expose it** until binder arrays and `PlatformSamplingReasons` are extended.

JMX `getSamplerDecisionCount("DROP", "<rule>")` would work immediately for any new `winningRule` string.

Evidence: binder uses fixed reason list; JMX reads dynamic map from `CompositeSampler`.

### 7.4 Is a new metric needed?

| Option | Assessment |
|---|---|
| JMX decision counter only | Sufficient for debugging; already exists |
| Extend `PlatformTracingSamplerMetricsBinder` | Required for Prometheus visibility of new reason |
| Separate `platform.tracing.sampler.rate_limit.dropped` | **Plan claim only** — not required if reason counter extended |

### 7.5 Actuator impact

`GET /actuator/tracing` exposes configured vs live sampling state via `SamplingControlClient` — **no rate-limit field today**.

Would need read-model extension if property added.

Evidence: `TracingActuatorEndpoint.java`, `DualChannelDriftDiagnostics.java`, `OtelEffectiveConfigSnapshot.java`.

---

## 8. Performance / Hot Path Analysis

### 8.1 Current hot path

Every span start → `CompositeSampler.shouldSample()` → full rule chain evaluation (worst case 7 rules) + counter increment.

Evidence: `CompositeSamplerBenchmark` javadoc; `CompositeSampler.shouldSample()`.

### 8.2 Disabled rate limit expected cost

**Plan claim:** zero cost when disabled (rule returns `null` immediately).

**Repository fact:** inserting an 8th rule adds at minimum one interface call + snapshot field read per evaluation when disabled — small but non-zero.

**Inference:** acceptable if branch is predictable and no CAS.

### 8.3 Enabled rate limit expected cost

External reference pattern: token-bucket with **CAS loop** on `trySpend(1.0)` per decision.

Evidence: `RateLimiter.trySpend()` in Dynatrace example tree (conceptual — do not copy).

**Risk:** CAS contention under multi-threaded span creation at burst.

### 8.4 Benchmark inventory

| Benchmark | Covers |
|---|---|
| `CompositeSamplerBenchmark` | force-header, parent-sampled, ratio-drop branches; @Threads(1,8) |
| `CompositeSamplerPolicyBranchesBenchmark` | policy branches |
| `CompositeSamplerConcurrentUpdateBenchmark` | concurrent config update |
| `QueueOfferBenchmark` | export queue |

Evidence: `platform-tracing-bench/src/jmh/java/...`

**No** `RateLimitGuardBenchmark` in repository.

### 8.5 Suggested performance gates

| Gate | Source in repo |
|---|---|
| JMH avg time regression vs baseline | `performance-budgets.yaml` → `jmh-latency-regression` **25% FAIL / 10% WARN** |
| JMH alloc regression | 15% FAIL / 5% WARN |
| M5 CPU delta | 3% hard gate (**PENDING**) |
| M5 RSS delta | 10% hard gate (**PENDING**) |

**Plan claim (+5% disabled overhead budget): NOT FOUND IN REPOSITORY** — only in draft plan.

Given M5 PENDING/FAIL documented in migration plan, any new hot-path work carries elevated regression risk.

Evidence: `docs/tracing/performance-budgets.yaml`; `platform-tracing-preservation-first-migration-plan.md`.

---

## 9. Implementation Impact Map

| Module | Files/classes likely affected | Type of change | Risk | Notes |
|---|---|---|---|---|
| `platform-tracing-otel` | `RateLimitPolicyRule` (new), token-bucket helper (new), `SamplingPolicyEngine.productionEngine()`, `SamplingPolicyReason`, `SamplingPolicyRuleNames`, `SamplingPolicySnapshot` | Feature + chain order change | **HIGH** | Core must stay OTel-free (`CorePolicyPackagePurityArchTest`) |
| `platform-tracing-api` | `PlatformSamplingReasons` (if exported reason code needed) | Contract extension | **MEDIUM** | Collector `EXPORTED` set is for sampled reasons only; rate-limit DROP may be metric-only like `kill_switch` |
| `platform-tracing-otel-javaagent-extension` | `CompositeSampler` (engine lifecycle?), `SamplerState`, `SamplerPolicyUpdate`, `SamplingPolicyOtelAdapter`, `PlatformTracingControl`/`MBean` | Adapter + JMX | **HIGH** | Engine rebuild or stateful rule decision |
| `platform-tracing-spring-boot-autoconfigure` | `TracingProperties.Sampling`, `SamplingRuntimeConfig`, `SamplingControlClient`, `RuntimeConfigApplier`, `PlatformTracingSamplerMetricsBinder`, actuator read models | Config + metrics | **MEDIUM** | Dual-channel alignment tests |
| `platform-tracing-bench` | New JMH benchmark (plan); update gate baselines | Perf evidence | **MEDIUM** | Required if hot path changes |
| `platform-tracing-test` | Harness cases for new rule | Test support | **LOW** | |
| `docs` | Characterization doc, ADR amendment | Governance | **LOW** | Rule order is frozen in characterization |

**No new module required** if implemented as policy rule — aligns with ADR "avoid new modules unless necessary."

---

## 10. Test Impact Map

| Test area | Existing tests | New/changed tests needed | Purpose |
|---|---|---|---|
| Rule chain order | `SamplingPolicyEngineTest.productionEngine_matchesCompositeSamplerRuleOrder` | Update expected count/names | Chain insertion |
| Characterization matrix | `SamplingDecisionMatrixCharacterizationTest` | New cases for rate-limit pass/drop/bypass | Preserve semantics |
| Snapshot compile | `SamplingPolicySnapshotTest` | New field validation | Limit in snapshot |
| Runtime update | `SamplerPolicyUpdateTest`, `SamplingPolicyRuntimeUpdateJmxTest` | Validation for limit bounds | JMX atomic update |
| Composite sampler | `CompositeSamplerTest` | Burst over limit behavior | Integration |
| Spring binding | `SamplingRuntimeConfigTest`, `RuntimeConfigApplierTest` | Property → JMX mapping | Dual-channel |
| Defaults alignment | `SharedDefaultsAlignmentTest` | Possibly none | Rate-limit default = disabled |
| Dual-channel drift | `DualChannelDriftDiagnosticsTest` | Optional | New property drift |
| ArchUnit purity | `CorePolicyPackagePurityArchTest`, `ExtensionNoSpringDependencyArchTest`, `OtelDirectIntegrationArchTest` | Must stay green | ADR guardrails |
| Hot-path regression | `CompositeSamplerBenchmark` | New disabled/enabled benchmarks | Perf gate |
| Collector contract | `CollectorPolicyContractTest` | Likely none | Rate-limit DROP not exported |

---

## 11. Overengineering Risk Assessment

| Risk | Score (1–10) | Rationale |
|---|---|---|
| No confirmed incident | **9** | Gate criteria explicitly unmet in repo |
| Duplicated protection (ratio/tail/memory/export) | **8** | Many layers already documented |
| Per-process vs cluster-wide confusion | **7** | Per-process cap ≠ cluster budget; easy to mis-sell |
| Stateful hot-path rule | **8** | Breaks stateless rule pattern; CAS on every span when enabled |
| Runtime engine rebuild complexity | **7** | `policyEngine` is final today |
| Adding JMX/property surface | **6** | Schema v1 already has 5 mutable fields |
| Hidden drift/reconciler scope creep | **6** | Another runtime field pushes toward desired-state layer |
| Copying OTel internals | **5** | ADR forbids; must reimplement pattern |
| Benchmarking burden | **7** | M5 already PENDING; new JMH baselines required |
| Metrics binder maintenance | **5** | Fixed reason lists need manual update |

**Overall overengineering risk if implemented without gate evidence: HIGH (8/10).**

---

## 12. Six Candidate Implementation Families for Perplexity

*Do not score here — describe only.*

### 1. No implementation / defer until evidence

- **Implement:** Nothing; document gate criteria and operational playbook using existing controls.
- **Avoid:** Hot-path change, config surface expansion, perf regression risk.
- **Affected modules:** docs only.
- **Why score:** Baseline option per committee rule; tests whether need is real.

### 2. Config-only operational playbook (existing ratio runtime update)

- **Implement:** Runbook: on burst, lower `platform.tracing.sampling.ratio` via JMX or Spring RefreshScope; restore after event.
- **Avoid:** New code; uses `setSamplingRatio` / `updateSamplingPolicy` already present.
- **Affected modules:** docs/ops; optional automation outside repo.
- **Why score:** Minimal engineering; semantic difference vs absolute cap.

### 3. Collector-side or SRE-side protection only

- **Implement:** Tune `memory_limiter`, tail sampling policies, ingress limits, HPA, OTLP receiver limits at infra layer.
- **Avoid:** Agent hot-path changes.
- **Affected modules:** `platform-tracing-collector-config`, K8s manifests (out of repo scope).
- **Why score:** Protects backend without per-pod token bucket; cluster-level view.

### 4. Minimal core `RateLimitPolicyRule` disabled by default

- **Implement:** 8th rule + snapshot field; default disabled (null limit); stateful limiter inside rule; no Spring/JMX until needed.
- **Avoid:** Dual-channel expansion initially; metrics binder changes.
- **Affected modules:** `platform-tracing-otel`, `platform-tracing-otel-javaagent-extension` (minimal).
- **Why score:** Smallest code path if gate passes; startup-only enable via agent property.

### 5. Full dual-channel runtime-mutable RateLimitGuard

- **Implement:** Property `platform.tracing.sampling.rate-limit-per-second`, JMX in `updateSamplingPolicy`, Spring applier, metrics binder, actuator read model.
- **Avoid:** Nothing — full feature surface.
- **Affected modules:** core, api, otel-extension, autoconfigure, bench, tests, docs.
- **Why score:** Matches draft plan; highest scope.

### 6. Alternative: exporter/BSP-side cap or drop-oldest tuning

- **Implement:** Tune `otel.bsp.max.queue.size`, export batching, drop-oldest thresholds; monitor `ExportDroppedOverflowTotal`.
- **Avoid:** Sampling semantics change; limits exported volume not span creation.
- **Affected modules:** agent config / `PlatformDropOldestExportSpanProcessor` tuning (topology = startup-only per ADR).
- **Why score:** Protects agent→collector pipe without new sampler rule; different failure mode.

---

## 13. Open Questions for LLM Scoring Pass

1. Is rate-limit guard **needed** given existing ratio + tail sampling + collector memory limiter + export queue?
2. If needed, **where in the 7-rule chain** should it sit?
3. **Per-process or per-route** granularity?
4. **Before or after** ratio rules?
5. **Runtime mutable** or startup-only enable?
6. **JMX dedicated setter** vs extend `updateSamplingPolicy` atomic payload?
7. **Separate metric** vs extend decision counters / binder reason list?
8. **Performance budget** — what regression threshold given M5 PENDING?
9. **Rejection criteria** — what evidence would falsify the need entirely?
10. Does **reactive ratio reduction** (existing JMX) satisfy SRE intent without new code?

---

## 14. Evidence Appendix

| File | One-line note |
|---|---|
| `platform-tracing-otel/.../SamplingPolicyEngine.java` | 7-rule production chain; `evaluate()` loop |
| `platform-tracing-otel/.../SamplingPolicyRule.java` | Rule SPI; null = pass |
| `platform-tracing-otel/.../SamplingPolicySnapshot.java` | Immutable policy inputs; no rate field |
| `platform-tracing-otel/.../SamplingPolicyDecision.java` | DROP/RECORD_AND_SAMPLE factories |
| `platform-tracing-otel/.../SamplingPolicyReason.java` | Enum of reasons; no RATE_LIMIT |
| `platform-tracing-otel/.../SamplingPolicyRuleNames.java` | winningRule string constants |
| `platform-tracing-otel/.../KillSwitchPolicyRule.java` | Example stateless rule |
| `platform-tracing-otel/.../DefaultRatioPolicyRule.java` | Terminal ratio rule |
| `platform-tracing-otel/.../ParentSampledPolicyRule.java` | Parent context rule |
| `platform-tracing-otel/.../SamplingPolicyEngineTest.java` | Chain order contract test |
| `platform-tracing-otel-javaagent-extension/.../CompositeSampler.java` | final `policyEngine`; hot path |
| `platform-tracing-otel-javaagent-extension/.../SamplerState.java` | Runtime snapshot + policySnapshot compile |
| `platform-tracing-otel-javaagent-extension/.../SamplerStateHolder.java` | CAS updates; tryApplyPolicyUpdate |
| `platform-tracing-otel-javaagent-extension/.../SamplerPolicyUpdate.java` | Validation + buildNext |
| `platform-tracing-otel-javaagent-extension/.../PlatformSamplerBuilder.java` | Agent startup sampler build |
| `platform-tracing-otel-javaagent-extension/.../SamplingPolicyOtelAdapter.java` | OTel mapping + metricRuleName |
| `platform-tracing-otel-javaagent-extension/.../PlatformDropOldestExportSpanProcessor.java` | Export queue drop-oldest |
| `platform-tracing-otel-javaagent-extension/.../configuration/ExtensionDefaults.java` | DEFAULT_SAMPLING_RATIO=0.1 |
| `platform-tracing-otel-javaagent-extension/.../configuration/ExtensionPropertyNames.java` | Dual-channel sampling keys |
| `platform-tracing-otel-javaagent-extension/.../configuration/OtelSdkDefaults.java` | BSP queue 2048 |
| `platform-tracing-otel-javaagent-extension/.../jmx/PlatformTracingControlMBean.java` | Sampling JMX API |
| `platform-tracing-api/.../PlatformSamplingReasons.java` | Exported/dropped reason contract |
| `platform-tracing-spring-boot-autoconfigure/.../TracingProperties.java` | Spring sampling schema v1 |
| `platform-tracing-spring-boot-autoconfigure/.../SamplingRuntimeConfig.java` | Runtime-mutable field list |
| `platform-tracing-spring-boot-autoconfigure/.../RuntimeConfigApplier.java` | Spring→JMX push |
| `platform-tracing-spring-boot-autoconfigure/.../SamplingControlClient.java` | JMX client; updateSamplingPolicy |
| `platform-tracing-spring-boot-autoconfigure/.../PlatformTracingSamplerMetricsBinder.java` | Fixed reason metrics |
| `platform-tracing-spring-boot-autoconfigure/.../PlatformTracingConfigMetricsBinder.java` | Config apply metrics |
| `platform-tracing-collector-config/.../otel-collector-gateway-tail-sampling.yaml` | Tail sampling + memory_limiter |
| `platform-tracing-bench/.../CompositeSamplerBenchmark.java` | Sampler hot-path JMH |
| `docs/tracing/performance-budgets.yaml` | JMH 25% regression gate; M5 PENDING |
| `docs/architecture/platform-tracing-sampling-characterization.md` | Frozen 7-rule order matrix |
| `docs/tracing/runtime-policy-control-architecture.md` | Policy vs topology; write path |
| `docs/decisions/ADR-otel-direct-integration.md` | No source copy; agent-first |
| `docs/decisions/ADR-runtime-config-policy-vs-topology.md` | Runtime policy vs startup topology |
| `docs/architecture/platform-tracing-preservation-first-migration-plan.md` | M5 FAIL baseline documented |
| `rate-limit_guard_arch_37ea302d.plan.md` | Draft plan (**Plan claim**, external to repo) |
| `E:\Platform_Traces_Examples\src\_Dynatrace\opentelemetry-java\sdk-extensions\jaeger-remote-sampler\RateLimitingSampler.java` | External reference: token bucket sampler |
| `E:\Platform_Traces_Examples\src\_Dynatrace\opentelemetry-java\sdk\common\...\RateLimiter.java` | External reference: CAS leaky bucket |

---

## 15. Unknowns / Not Found

| Item | Status |
|---|---|
| Documented incident (ratio + tail sampling failed) | **NOT FOUND IN REPOSITORY** |
| Load test: collector inlet exceeds budget under burst | **NOT FOUND IN REPOSITORY** |
| Contractual SLO `N traces/sec per service` | **NOT FOUND IN REPOSITORY** |
| `RateLimitPolicyRule` or rate-limit property | **NOT FOUND IN REPOSITORY** |
| Established +5% disabled hot-path JMH budget | **Plan claim only** — repo uses 25%/10% JMH gates |
| Cluster-wide rate coordination (Redis/shared state) | **NOT FOUND IN REPOSITORY** |
| `PlatformTracingConfigMetricsBinder` class name in user prompt | Found as `PlatformTracingConfigMetricsBinder` ✓ |
| Whether SRE burst requirement exists outside repo | **UNKNOWN** — not in git docs |
| Production rollout status | Docs state pre-production / not deployed (`ADR-runtime-config-policy-vs-topology.md`) |

---

*End of dossier. For Perplexity scoring: use §11–§13 with §2 gate verdict as constraint.*
