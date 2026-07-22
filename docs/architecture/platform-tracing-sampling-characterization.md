# Platform Tracing — Sampling characterization (PR-5A)

## Purpose

Freeze **current** `CompositeSampler` rule-chain behavior before PR-6 extraction of sampling policy into `platform-tracing-core`. These tests are golden-master / characterization tests: they capture observed behavior, not target architecture.

## Scope of PR-5A

- Test-only changes in `platform-tracing-otel-javaagent-extension` and `platform-tracing-test`
- Reusable harness helpers (`SamplingDecisionCase`, `SamplingContextFactory`, `SamplingAssertions`)
- Documentation of actual rule order and decision matrix
- No production code moves or behavior changes

## Non-goals

- Extract sampling policy to core (PR-6)
- Change rule order, ratios, or runtime JMX/Actuator control
- Add OTel dependencies to core policy packages
- Full JMH baseline capture
- Scrubbing/validation characterization (PR-5B)

## Current sampling rule order

**Source of truth:** `SamplingPolicyEngine.productionEngine()` (`platform-tracing-core`), delegated by `CompositeSampler` (PR-6B+).

| # | Core policy rule | metric key |
|---|------------------|------------|
| 1 | `KillSwitchPolicyRule` | `kill_switch` |
| 2 | `HardDropPolicyRule` | `hard_drop` |
| 3 | `ForceHeaderPolicyRule` | `force_header` |
| 4 | `QaTracePolicyRule` | `qa_trace` |
| 5 | `ParentSampledPolicyRule` | `parent_decision` |
| 6 | `RouteRatioPolicyRule` | `route_ratio` |
| 7 | `DefaultRatioPolicyRule` | `default_ratio` |

Legacy otel-extension `*Rule` classes (`KillSwitchRule`, …) were removed in PR-6C; behavior unchanged.

**Note:** This order differs from some migration-plan sketches that place `HardDropRule` after ratio rules or `ParentDecisionRule` last. PR-6 must preserve **this** order unless an explicit ADR change is approved with updated characterization tests.

### Priority implications (observed)

- Kill switch drops without `platform.sampling.reason` on span (metric reason only).
- Hard drop wins over force header, QA, parent, and route ratio.
- Force header wins over QA and parent decision.
- Parent decision (sampled or not) runs **before** route/default ratio — unsampled parent drops even when route ratio is `1.0`.
- Route ratio wins over global default ratio when `url.path` prefix matches. **PR-9G:** overlapping prefixes use **longest-prefix-wins** (prefix length descending, lexicographic ascending tie-breaker). Sorting happens once at `SamplingPolicySnapshot` compile time; `RouteRatioPolicyRule` first-match over the sorted array. No sorting on `shouldSample` hot path. Configured map/list order is input/display order only.
- Missing/empty `url.path` skips hard drop (prefix) and route ratio; falls through to default ratio.

## Characterized behavior matrix

| caseId | killSwitch | forceHeader | qaTrace | route (url.path) | routeRatio | defaultRatio | parent | expectedDecision | expectedReason | winningRule |
|--------|------------|-------------|---------|------------------|------------|--------------|--------|------------------|----------------|-------------|
| KS-01 | off | on | on | `/api/orders` | — | 1.0 | — | DROP | *(none on span)* | `kill_switch` |
| HD-01 | on | on | — | `/actuator/health` | — | 1.0 | — | DROP | `drop_path` | `hard_drop` |
| FH-01 | on | on | — | — | — | 0.0 | — | RECORD_AND_SAMPLE | `force_header` | `force_header` |
| QA-01 | on | — | on | — | — | 0.0 | — | RECORD_AND_SAMPLE | `qa_trace` | `qa_trace` |
| FH-QA | on | on | on | — | — | 0.0 | — | RECORD_AND_SAMPLE | `force_header` | `force_header` |
| PD-S | on | — | — | — | — | 0.0 | sampled | RECORD_AND_SAMPLE | `parent_sampled` | `parent_decision` |
| PD-D | on | — | — | — | — | 1.0 | not sampled | DROP | `parent_drop` | `parent_decision` |
| PD-RR | on | — | — | `/api/v1/critical/...` | 1.0 | 1.0 | not sampled | DROP | `parent_drop` | `parent_decision` |
| RR-01 | on | — | — | `/api/v1/critical/...` | 1.0 | 0.0 | — | RECORD_AND_SAMPLE | `route_ratio` | `route_ratio` |
| RR-D | on | — | — | `/api/v1/noisy/...` | 0.0 | 1.0 | — | DROP | `route_ratio_drop` | `route_ratio` |
| GR-01 | on | — | — | `/api/v1/orders` | *(no match)* | 1.0 | — | RECORD_AND_SAMPLE | `global_ratio` | `default_ratio` |
| GR-D | on | — | — | — | — | 0.0 | — | DROP | `global_ratio_drop` | `default_ratio` |

Matrix executed by `SamplingDecisionMatrixCharacterizationTest` via `SamplingDecisionCase` records.

**Winning rule** is verified through `CompositeSampler.getDecisionCount(decision, ruleName)` — internal metric key, not always equal to `platform.sampling.reason`.

## PR-6B OTel adapter mapping

| Core `SamplingPolicyDecision` | OTel `SamplingResult` | Span `platform.sampling.reason` |
|-------------------------------|----------------------|----------------------------------|
| DROP + KILL_SWITCH | `DROP` (no attrs) | *(none)* |
| DROP + HARD_DROP | `DROP` | `drop_path` |
| DROP + PARENT_DROP | `DROP` | `parent_drop` |
| DROP + ROUTE_RATIO_DROP | `DROP` | `route_ratio_drop` |
| DROP + DEFAULT_RATIO_DROP | `DROP` | `global_ratio_drop` |
| RECORD_AND_SAMPLE + FORCE_HEADER / QA / PARENT | `RECORD_AND_SAMPLE` | matching reason code |
| RECORD_AND_SAMPLE + ROUTE_RATIO / DEFAULT_RATIO | `ForwardingSamplingResult(RECORD_AND_SAMPLE)` | `route_ratio` / `global_ratio` |
| ABSTAIN (engine fallback only) | `DROP` | *(none)* — metric `fallback_drop` |

PR-6B does **not** introduce runtime dynamic config; `SamplerStateHolder` update semantics unchanged.

## Tests added (PR-5A)

| Test class | Module | Role |
|------------|--------|------|
| `SamplingRuleOrderCharacterizationTest` | otel-extension | Policy engine rule order via `CompositeSampler.policyEngine()` |
| `SamplingDecisionMatrixCharacterizationTest` | otel-extension | Parameterized matrix (12 cases) |
| `SamplingRuleChainCharacterizationTest` | otel-extension | Priority interactions |
| `SamplerStateCharacterizationTest` | otel-extension | Runtime `SamplerStateHolder.update` effects |
| `SamplingRuntimeUpdateCharacterizationTest` | otel-extension | Route-ratio update + concurrent read safety |
| `SamplingContextFactoryTest` | platform-tracing-test | Harness factory smoke |

### Harness additions (`platform-tracing-test`)

| Type | Purpose |
|------|---------|
| `SamplingDecisionCase` | Matrix row model |
| `SamplingContextFactory` | Force/QA/parent contexts |
| `SamplingAssertions` | Decision + `platform.sampling.reason` helpers |

## Existing tests preserved

All pre-PR-5A sampling tests remain unchanged and must stay green:

- `CompositeSamplerTest`
- `CompositeSamplerEdgeCasesTest`
- `CompositeSamplerRouteRatioTest` *(RouteRatio coverage)*
- `SamplerStateHolderTest`
- `SamplerRuntimeUpdateConcurrencyTest`
- `PlatformSamplerProviderTest`
- `SafeSamplerTest`

## Core-side characterization

**PR-6A (2026-06-12):** pure Java sampling policy foundation added in `platform-tracing-core` (`core.sampling` — request/decision/snapshot/engine, `KillSwitchPolicyRule`, `HardDropPolicyRule`). No runtime sampling path changed; `CompositeSampler` still uses otel-extension rules. JMH baseline `windows-i5-13500-pr0` remains reference for PR-6B delegation.

**PR-6B (2026-06-12):** `CompositeSampler` delegates to `SamplingPolicyEngine.productionEngine()` via `SamplingPolicyOtelAdapter`. Full 7-rule pure-Java chain in core; OTel mapping in otel-extension. `SamplerState` compiles `SamplingPolicySnapshot` at snapshot build time. No dynamic config / JMX / Actuator changes. Semantic parity verified by PR-5A characterization matrix + adapter tests. Rule order unchanged from PR-5A table above.

**PR-6C (2026-06-12):** Removed legacy otel-extension `SamplingRule` path and unused OTel `traceIdRatioBased` samplers from `SamplerState`. Production path remains `CompositeSampler` → `SamplingPolicyEngine` → `SamplingPolicyOtelAdapter`. No behavior change intended. Dynamic runtime config still out of scope. Route prefix matching order unchanged (HashMap iteration). Fresh CompositeSampler JMH run: 24/24 entries matched against `windows-i5-13500-pr0` baseline.

**PR-6C1 (2026-06-12):** Fixed `jmhCompareBaseline` JSON key matching (GString → canonical String keys via Jackson comparator). Gate now matches 24/24 CompositeSampler entries reliably; `gateUnreliable()` when matched=0.

**PR-6C2 (2026-06-12):** Hot-path allocation micro-optimizations (cached `SamplingResult` / ratio decisions, no per-call `Attributes.of`). Result: 0 alloc FAIL, 0 alloc WARN; avgt latency mostly improved; semantic parity preserved.

**PR-6C3 (2026-06-12):** JMH gate policy clarified: **avgt latency + gc.alloc.rate.norm are hard gates**; **sample-mode latency is diagnostic by default** (reported, not blocking `-PjmhFailOnRegression`). Optional strict sample gate: `-PjmhFailOnSampleRegression=true`. Tail latency release confidence remains macro-load HDR/JFR evidence per ADR-performance-model.

**PR-6D (2026-06-12):** Runtime sampling policy update via agent JMX bridge. Atomic `updateSamplingPolicy(enabled, defaultRatio, droppedRoutes[], forceRecordValues[], routeRatioPrefixes[], routeRatioValues[], source)` on `PlatformTracingControlMBean`; `SamplingControlClient` invokes one MBean call per domain update. Path: JMX → `SamplerStateHolder.tryUpdate` → validated `SamplerState` + compiled `SamplingPolicySnapshot` → lock-free read in `CompositeSampler`. Last-known-good on invalid config; version increments only on successful publish; `source` recorded. Spring `RuntimeConfigApplier` pushes with source `spring-runtime-config` (RefreshScope listener unchanged — still out of direct scope). No hot-path / semantics change.

**PR-6E (2026-06-12):** Spring-side sampling runtime config schema v1 + thin reconciler. `TracingProperties.Sampling` fields (`enabled`, `ratio`, `dropPaths`, `forceRecordHeaderValues`, `routeRatios`) formalized as runtime-mutable schema v1; `SamplingRuntimeConfig` view extracts domain; `RuntimeConfigApplier` → `SamplingControlClient.updateSamplingPolicy(SamplingRuntimeConfig)` → exactly one atomic JMX call with `source=spring-runtime-config`. `routeRatios` Map converts to parallel arrays preserving `LinkedHashMap` insertion order for **input/display order only** (matching semantics unchanged until PR-9G). Refresh path: `RefreshScopeRefreshedEvent` listener (not `EnvironmentChangeEvent`); `RuntimeConfigApplier` not refresh-scoped. Invalid agent-side update: rejected, LKG active, version unchanged. No scrubbing/validation runtime update; no hot-path change.

**PR-9G (2026-06-13):** Deterministic route-ratio selection. `SamplingPolicySnapshot.normalizeRouteRatios()` sorts route-ratio prefixes at snapshot compile time: (1) prefix length **descending** (longest-prefix-wins), (2) lexicographic **ascending** tie-breaker for equal-length prefixes. `RouteRatioPolicyRule` unchanged — first-match over the sorted array. Caller-provided list is not mutated. Runtime path (`SamplerPolicyUpdate` → `SamplerState` → `SamplingPolicySnapshot.fromConfiguration`) uses the same normalization. No sorting on `shouldSample` hot path. Non-overlapping route-ratio behavior unchanged. Operators with overlapping prefixes see effective behavior change from unspecified/hash-order-dependent to deterministic most-specific-prefix matching.

### Route-ratio matching (PR-9G)

| Aspect | Behavior |
|--------|----------|
| Overlapping prefixes | Longest matching prefix wins |
| Same-length tie | Lexicographic ascending prefix |
| No prefix match | `RouteRatioPolicyRule` abstains; default ratio applies |
| Configured order | Input/display order only (Spring `LinkedHashMap`, JMX arrays) — not matching semantics |
| Active matching order | Compile-time sort in `SamplingPolicySnapshot`; immutable in snapshot |
| Hot path | No sort; reads pre-sorted `RouteRatioPrefix[]` |

**Example:**

| Configured prefix | Ratio |
|-------------------|-------|
| `/api` | 0.10 |
| `/api/v2` | 0.50 |
| `/api/v2/orders` | 1.00 |

Request `url.path` = `/api/v2/orders/123` → selected **`/api/v2/orders` = 1.00** (longest prefix match).

**Operator migration note:** If overlapping route-ratio prefixes were configured before PR-9G, effective sampling may change from hash-order-dependent to longest-prefix-wins. Review overlapping configs after upgrade.

**PR-7A (2026-06-13):** Scrubbing runtime foundation (agent-side): `ScrubbingSnapshot` + `ScrubbingPolicyHolder`; `ScrubbingSpanProcessor` reads immutable snapshot lock-free. Regex compiled at snapshot build. Sampling remains reference pattern for Spring/JMX reconciler (PR-7B/7C for scrubbing).

**PR-7C (2026-06-13):** Spring scrubbing schema v1 + `RuntimeConfigApplier` reconciler; `source=spring-runtime-config`; one client call per domain (see scrubbing characterization doc).

## What must happen in PR-6

1. Move rule value objects / pure policy to `platform-tracing-core` without changing characterized decisions.
2. Keep `CompositeSampler` as OTel adapter delegating to core policy.
3. Re-run all `*Characterization*Test` and existing sampler tests green.
4. Require full JMH baseline captured **before** PR-6 merge (hard gate).

## Gates and readiness

```text
PR-5A does not move sampling production code.
PR-5A does not extract sampling policy to core.
PR-5A does not change runtime behavior.
PR-5A is allowed before full JMH only because it is test-only.
PR-6 remains blocked until full JMH baseline is captured and saved.
```

Official JMH commands (unchanged):

```powershell
./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g
./gradlew :platform-tracing-bench:jmhSaveBaseline
```

See also: [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md), [perf-baseline-fix-report.md](perf-baseline-fix-report.md), [pr-0-baseline-results-2026-06-12-complete-or-waived.md](pr-0-baseline-results-2026-06-12-complete-or-waived.md).

**PR-9A:** Unified runtime policy architecture consolidated — see [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md). `SamplingControlClient` rename deferred.

## Related migration plan

PR-5A corresponds to the sampling slice of PR-5 in [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md) (split PR-5A / PR-5B).
