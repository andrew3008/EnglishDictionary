# Rate-Limit Guard F3 Technical Spike Plan

> **Status:** Proposed — bounded technical spike only (no production approval)
> **Date:** 2026-06-16
> **Repository:** `spring-boot-platform-tracing` (`e:\Platform_Traces`)
> **Related evidence:** [rate-limit-guard-research-dossier.md](./rate-limit-guard-research-dossier.md)
> **F3 definition:** Startup-only minimal `RateLimitPolicyRule`, **disabled by default**

---

## 1. Decision Context

Three model passes informed the architecture committee evaluation:

| Model | Position | Essence |
|---|---|---|
| **Claude Sonnet** | `DEFER_UNTIL_LOAD_TEST_OR_SLO` | Do not implement until incident, load test, or contractual traces/sec SLO exists |
| **Gemini** | `IMPLEMENT_STARTUP_ONLY_MINIMAL` | Proceed with minimal startup-only rule; avoid full runtime-mutable surface |
| **Kimi** | `RUN_F3_TECHNICAL_SPIKE_ONLY` | Do not implement production feature now; run bounded spike to prove/disprove F3 feasibility |

**Repository gate verdict (from dossier):** `GATE_NOT_READY` — no incident, load test, or traces/sec SLO found in repository.

Evidence: [rate-limit-guard-research-dossier.md §2.4](./rate-limit-guard-research-dossier.md)

**Chosen compromise:**

```text
Do not implement production Rate Limit Guard now.
Do not implement full runtime-mutable Rate Limit Guard (F4+).
Allow only a bounded technical spike for F3:
  startup-only, disabled by default, no Spring/JMX/Actuator/Micrometer expansion.
```

This spike plan implements the **Kimi position** while preserving Claude's gate (spike does not substitute for load-test/SLO evidence) and borrowing Gemini's startup-only scope boundary.

---

## 2. Spike Goal

**This spike does not approve production implementation.**

It only proves or disproves whether **F3** is technically feasible within existing architecture constraints, by answering exactly these ten questions:

| # | Question |
|---|---|
| Q1 | Can F3 preserve `CompositeSampler.policyEngine` as `final`? |
| Q2 | Can disabled mode preserve the exact current 7-rule engine? |
| Q3 | Can enabled mode build an 8-rule engine only at startup? |
| Q4 | Can a stateful `RateLimitPolicyRule` be safely introduced as the only exception in a stateless rule chain? |
| Q5 | Can token-bucket implementation pass concurrency tests? |
| Q6 | Can disabled overhead remain statistically indistinguishable from baseline? |
| Q7 | Can enabled overhead stay within existing JMH budgets? |
| Q8 | Can parent/force/QA semantics remain correct? |
| Q9 | Can observability rely only on existing JMX decision counters? |
| Q10 | Does F3 provide enough JVM/agent protection to justify production implementation? |

**Spike deliverable:** a short **F3 Feasibility Report** (separate doc or PR description) with pass/fail per question, benchmark numbers, and recommendation: proceed to bounded F3 implementation / keep spike-only / reject entirely.

**Spike does NOT deliver:** production feature, runtime config, ops runbook, or gate evidence for operational need.

---

## 3. F3 Scope

### Allowed

- Startup-only enablement via **agent extension startup config** (new agent property acceptable **only inside spike implementation PR**, not in this planning doc's scope)
- Default **disabled** — absent property ⇒ behavior identical to today
- Spike code confined to **`platform-tracing-core`** + **`platform-tracing-otel-javaagent-extension`** (no new modules)
- Unit tests, concurrency tests, JMH benchmarks **in spike PR** (planned here, not created now)
- Existing JMX sampler decision counters (`getSamplerDecisionCount`, `getSamplerDecisionCounts`)
- `winningRule = "rate_limit"` for DROP decisions (internal counter key; span attribute mapping TBD in spike)
- Per-process JVM token bucket only

### Forbidden (hard constraints for spike and any follow-on)

| Forbidden | Rationale |
|---|---|
| Full **F4** (runtime-mutable rate limit) | Out of Kimi scope |
| `updateSamplingPolicy` payload extension | No JMX schema change in spike |
| `TracingProperties` / Spring binding | No autoconfigure change |
| `RuntimeConfigApplier` change | Dual-channel out of scope |
| `SamplingControlClient` change | JMX client out of scope |
| Micrometer binder expansion | Fixed reason lists in `PlatformTracingSamplerMetricsBinder` |
| Actuator read-model extension | Out of scope |
| Collector-side / cluster-wide limiter | Different layer |
| Redis / external coordinator | NOT FOUND IN REPOSITORY as platform pattern |
| Copy OTel internal `RateLimiter` source | ADR: no source copy |

Evidence: [ADR-otel-direct-integration.md](../decisions/ADR-otel-direct-integration.md); dossier §3, §5, §7.

---

## 4. Proposed Startup Architecture

**Candidate design (to be validated by spike — not implemented in this document):**

```text
PlatformSamplerBuilder.build(config)
  → read startup-only: platform.tracing.sampling.rate-limit-per-second (optional, spike-only property)
  → if absent/null/<=0:
        policyEngine = SamplingPolicyEngine.productionEngine()     // exact 7 rules
     else:
        policyEngine = SamplingPolicyEngine.productionEngineWithRateLimit(maxPerSec)  // 8 rules
  → CompositeSampler(holder, policyEngine)   // policyEngine field remains final

Rule order when enabled (8 rules):
  KillSwitch → HardDrop → ForceHeader → QaTrace → ParentSampled
    → RateLimit → RouteRatio → DefaultRatio
```

### Architectural invariants (target)

| Invariant | Mechanism |
|---|---|
| `CompositeSampler.policyEngine` stays **`final`** | Engine instance chosen once in constructor; no post-startup reassignment |
| Disabled = **exact 7-rule engine** | Factory returns `productionEngine()` — same rule classes, same count, same order as today |
| Enabled = **8-rule engine at startup only** | Factory inserts `RateLimitPolicyRule` at index 5 (after parent, before route ratio); no runtime rebuild |
| `RateLimitPolicyRule` = **only stateful rule** | Holds token-bucket instance; all other rules remain stateless singletons per engine |
| Rate limit position | After `ParentSampledPolicyRule`, before `RouteRatioPolicyRule` — Force/QA/parent decisions win |

Evidence for current baseline: `SamplingPolicyEngine.productionEngine()`; `CompositeSampler` constructor currently calls `productionEngine()` once.

Evidence: `platform-tracing-core/.../SamplingPolicyEngine.java`; `platform-tracing-otel-javaagent-extension/.../CompositeSampler.java`.

**Note:** `ExtensionPropertyNames` today has **no** rate-limit key — **NOT FOUND IN REPOSITORY**. Spike may introduce a single agent property constant; must not wire Spring/dual-channel.

---

## 5. Design Options to Validate

| Option | Description | Expected risk | Accept / reject criteria |
|---|---|---:|---|
| **A. Always include disabled 8th rule** | `RateLimitPolicyRule` always in chain; `evaluate()` returns `null` when limit absent | Low implementation risk; **medium semantic/benchmark risk** | **REJECT for F3** if disabled path uses 8-rule engine (fails Q2) or measurably slower than 7-rule baseline (fails Q6) |
| **B. Conditional 7/8 rule engine (recommended)** | Factory builds `productionEngine()` or `productionEngineWithRateLimit(n)` at startup | Low–medium; requires constructor/factory change | **ACCEPT** if Q1–Q3 pass: final field, disabled=7 rules, enabled=8 rules |
| **C. Engine from `SamplerState`** | Rebuild engine when state changes | High — conflicts with runtime updates today | **REJECT for F3** — implies runtime mutability path (F4) |
| **D. Wrapper sampler around `CompositeSampler`** | Outer OTel `Sampler` applies rate limit before/after composite | Medium; breaks single `PlatformManagedSampler` model | **REJECT** — duplicates chain, complicates JMX/metrics delegation |

**Spike primary hypothesis:** Option **B** satisfies Q1–Q3 without F4 surface area.

---

## 6. Token Bucket Options

Conceptual reference only (do **not** copy OTel SDK `io.opentelemetry.sdk.internal.RateLimiter`):

Evidence: external pattern in `E:\Platform_Traces_Examples\src\_Dynatrace\opentelemetry-java\sdk-extensions\jaeger-remote-sampler\RateLimitingSampler.java` — leaky bucket via `trySpend(1.0)`.

| Option | Pros | Cons | Spike verdict criteria |
|---|---|---|---|
| **AtomicLong CAS token bucket** | Lock-free; matches conceptual OTel/Jaeger pattern; no synchronized pin | CAS retry under contention; must prove bounded retries | **PASS** if concurrency tests pass and JMH @Threads(8) within budget |
| **LongAdder approximate limiter** | Very fast increments | Not a true token bucket; burst accuracy poor | **REJECT** unless committee accepts approximate cap |
| **`synchronized` limiter** | Simple correctness | Pin under multi-threaded span start; latency tail risk | **REJECT** if p99 regression > JMH WARN threshold at @Threads(8) |
| **Striped limiter (per-thread buckets)** | Reduces contention | Over-admits vs global cap (N × limit); wrong semantics for traces/sec | **REJECT for F3** — violates per-process cap semantics |
| **Conceptual OTel pattern (reimplemented)** | ADR-compliant; nanosecond credit accrual | More code to validate | **PASS** if behavior matches reference semantics without source copy |

**Spike default candidate:** AtomicLong CAS token bucket with nanosecond refill (reimplemented, not copied).

---

## 7. Required Semantic Tests

Spike PR must include characterization-style tests (extend patterns from `SamplingDecisionMatrixCharacterizationTest`, `SamplingPolicyEngineTest`).

| Case ID | Scenario | Expected outcome | Rationale |
|---|---|---|---|
| SEM-01 | ForceHeader active, rate limit exhausted | **RECORD_AND_SAMPLE** (force wins) | Force runs before rate limit |
| SEM-02 | QaTrace active, rate limit exhausted | **RECORD_AND_SAMPLE** | QA runs before rate limit |
| SEM-03 | Parent SAMPLED, rate limit exhausted | **RECORD_AND_SAMPLE** | Parent runs before rate limit |
| SEM-04 | Parent NOT_SAMPLED, rate limit available | **DROP** (parent_drop) | Rate limit must not override parent drop |
| SEM-05 | Root trace (parent ABSENT), rate limit exhausted | **DROP** (rate_limit) | Core protection case |
| SEM-06 | Root trace, under limit, route ratio 1.0 | **RECORD_AND_SAMPLE** (route_ratio) | Rate limit passes through |
| SEM-07 | Root trace, under limit, default ratio 0.0 | **DROP** (global_ratio_drop) | Ratio still applies after allow |
| SEM-08 | Hard drop path, rate limit available | **DROP** (drop_path) | Hard drop before rate limit |
| SEM-09 | Kill switch off, rate limit available | **DROP** (kill_switch) | Kill switch before rate limit |
| SEM-10 | Disabled F3 (7-rule engine), any request | Same as baseline matrix | Q2 regression guard |

Evidence for priority order: [platform-tracing-sampling-characterization.md](./platform-tracing-sampling-characterization.md).

---

## 8. Required Concurrency Tests

Spike PR must include dedicated concurrency tests (JUnit 5, multi-thread; not created in this planning step).

| Test | Requirement |
|---|---|
| CON-01 | **No negative tokens** — balance never below zero after any interleaving |
| CON-02 | **No deadlock / livelock** — burst test completes within timeout (e.g. 30s) at @Threads ≥ 32 |
| CON-03 | **Upper bound under burst** — over 1s window, accepted decisions ≤ `limit + tolerance` (define tolerance e.g. 1–2% in spike report) |
| CON-04 | **Multi-thread deterministic enough** — same seed/replay yields stable accept count ± tolerance |
| CON-05 | **No allocation growth on hot path** (if measurable) — JMH `gc.alloc.rate.norm` for enabled path documented; **committee decision** if no baseline exists |

---

## 9. Required JMH Benchmarks

Extend existing suite in `platform-tracing-bench` (plan only — do not create now).

| Benchmark | Mode | Threads | Purpose |
|---|---|---|---|
| **BL-7** | Current `CompositeSamplerBenchmark` branches | 1, 8 | 7-rule baseline (existing) |
| **F3-DIS** | Same branches, F3 disabled (7-rule engine) | 1, 8 | Q6: indistinguishable from BL-7 |
| **F3-EN-BELOW** | Enabled, steady under limit | 1, 8 | Q7: enabled cost when allowing |
| **F3-EN-ABOVE** | Enabled, sustained over limit (DROP path) | 1, 8 | Q7: enabled cost when rejecting |
| **F3-ALLOC** | Enabled above limit | 1, 8 | Allocation rate vs baseline |

Evidence: existing `CompositeSamplerBenchmark.java`; `CompositeSamplerPolicyBranchesBenchmark.java`; `CompositeSamplerConcurrentUpdateBenchmark.java`.

### Pass/fail thresholds (from repository)

From [performance-budgets.yaml](../tracing/performance-budgets.yaml):

| Metric | FAIL | WARN |
|---|---|---|
| `avgt_regression_pct_vs_baseline` (jmh-suite) | **25%** | **10%** |
| `gc_alloc_rate_norm_regression_pct_vs_baseline` | **15%** | **5%** |

**F3-DIS (disabled):** must meet **WARN** threshold (10% avg, 5% alloc) vs BL-7 — target "statistically indistinguishable" (Q6); ideally < 3% (committee may tighten).

**F3-EN-* (enabled):** must not exceed **FAIL** threshold vs BL-7; WARN exceedance requires explicit committee acceptance.

**M5 macro perf:** remains **PENDING** separately — spike JMH does not clear M5 gate.

**+5% disabled budget:** **NOT FOUND IN REPOSITORY** (draft plan only) — use YAML thresholds above.

---

## 10. Observability During Spike

**Use only existing mechanisms:**

| Signal | Usage |
|---|---|
| `CompositeSampler.getDecisionCount("DROP", "rate_limit")` | Primary spike metric |
| `getSamplerDecisionCounts()` | Snapshot all keys |
| JMX via `PlatformTracingControlMBean.getSamplerDecisionCount` | Agent-side read in tests/smoke |

**Do not add:**

- `PlatformTracingSamplerMetricsBinder` reason entries
- Actuator fields
- New Micrometer metric names

**Span attribute:** spike may map DROP to internal `winningRule = "rate_limit"`. Whether `platform.sampling.reason` is exported on span is **spike decision** — dossier notes DROP reasons like `kill_switch` are metric-only. Align with `SamplingPolicyOtelAdapter` patterns.

Evidence: `CompositeSampler.recordDecision()`; `PlatformTracingSamplerMetricsBinder` fixed reason lists.

---

## 11. Acceptance Criteria

F3 may proceed to **production implementation** (separate committee decision) **only if all** hold:

1. **Q1–Q3 PASS:** `policyEngine` final; disabled = exact 7-rule engine; enabled = 8-rule startup-only.
2. **Q6 PASS:** F3-DIS JMH within WARN budget vs baseline (ideally indistinguishable).
3. **Q7 PASS:** F3-EN-* within FAIL budget; WARN exceedance documented and accepted.
4. **Q5 PASS:** All concurrency tests (CON-01..04) pass.
5. **Q8 PASS:** All semantic tests (SEM-01..10) pass.
6. **Q4 PASS:** Committee explicitly accepts **stateful rule exception** in otherwise stateless chain.
7. **Q9 PASS:** JMX decision counters sufficient for spike validation (no binder needed).
8. **Scope:** No Spring/JMX runtime config / Actuator / Micrometer binder added in spike.
9. **Documentation:** Explicit statement — **per-process guard, not cluster-wide protection**.
10. **Q10:** Committee judgment — spike evidence plus (still required) **operational gate** (load test or SLO) before production.

**Important:** Passing F3 spike ≠ overriding `GATE_NOT_READY` from dossier.

---

## 12. Rejection Criteria

Reject F3 (do not proceed even to minimal production) if any occur:

| # | Condition |
|---|---|
| R1 | Disabled path requires 8-rule engine (no-op rule) **and** fails Q6 |
| R2 | JMH regression exceeds **FAIL** thresholds in [performance-budgets.yaml](../tracing/performance-budgets.yaml) |
| R3 | Token bucket CAS contention causes unacceptable tail latency at @Threads(8) |
| R4 | Parent / force / QA semantics ambiguous or violated (SEM failures) |
| R5 | Implementation requires runtime mutability or `SamplerState`/JMX changes to activate |
| R6 | Committee confirms **cluster-wide** protection is required |
| R7 | No one can define target **N traces/sec** for acceptance testing |
| R8 | Stateful rule exception rejected by architecture board |
| R9 | Disabled path cannot preserve `SamplingPolicyEngineTest.productionEngine_matchesCompositeSamplerRuleOrder` contract without forked baseline |

---

## 13. Files Likely Touched if Spike Proceeds

**Inventory only — do not edit in this planning task.**

### `platform-tracing-core`

| File | Change type |
|---|---|
| `sampling/SamplingPolicyEngine.java` | Add `productionEngineWithRateLimit(int)` factory |
| `sampling/RateLimitPolicyRule.java` | **New** — stateful rule |
| `sampling/TokenBucketRateLimiter.java` (or similar name) | **New** — pure Java limiter |
| `sampling/SamplingPolicyReason.java` | Add `RATE_LIMIT` enum value |
| `sampling/SamplingPolicyRuleNames.java` | Add `RATE_LIMIT` constant |
| `sampling/SamplingPolicyDecision.java` | No change expected |
| `sampling/SamplingPolicySnapshot.java` | **Optional** — spike may avoid snapshot field if limit is rule-constructor arg only |
| `src/test/.../SamplingPolicyEngineTest.java` | Extend chain order tests |
| `src/test/.../RateLimitPolicyRuleTest.java` | **New** |
| `src/test/.../TokenBucketRateLimiterConcurrencyTest.java` | **New** |
| `src/test/.../arch/CorePolicyPackagePurityArchTest.java` | Must stay green |

### `platform-tracing-otel-javaagent-extension`

| File | Change type |
|---|---|
| `sampler/CompositeSampler.java` | Constructor overload or factory param for engine selection |
| `sampler/PlatformSamplerBuilder.java` | Read startup-only rate-limit property; select engine |
| `sampler/SamplingPolicyOtelAdapter.java` | Map `RATE_LIMIT` DROP to OTel result + metric key |
| `configuration/ExtensionPropertyNames.java` | Add startup-only property constant (spike) |
| `configuration/ExtensionDefaults.java` | Document default disabled |
| `src/test/.../CompositeSamplerTest.java` | Semantic cases |
| `src/test/.../SamplingDecisionMatrixCharacterizationTest.java` | Optional SEM-10 baseline guard |

### `platform-tracing-bench`

| File | Change type |
|---|---|
| `CompositeSamplerBenchmark.java` | Baseline reference |
| `RateLimitGuardF3Benchmark.java` | **New** — F3-DIS / F3-EN-* modes |

### Explicitly NOT touched in F3 spike

- `platform-tracing-spring-boot-autoconfigure/**`
- `SamplingControlClient.java`
- `RuntimeConfigApplier.java`
- `TracingProperties.java`
- `PlatformTracingSamplerMetricsBinder.java`
- `TracingActuatorEndpoint.java`
- `PlatformTracingControlMBean.java` (no new operations)
- `platform-tracing-collector-config/**`

---

## 14. Final Recommendation

```text
RUN_F3_TECHNICAL_SPIKE_ONLY
```

**Rationale:**

- Repository gate for operational need remains **`GATE_NOT_READY`** (dossier §2.4).
- Full runtime-mutable rate limit (F4) is over-scoped and high-risk given M5 PENDING perf state.
- F3 spike is **bounded**, answers ten concrete feasibility questions, and preserves Claude's deferral until load-test/SLO while allowing Gemini's startup-only shape to be **proven**, not assumed.
- If spike passes, committee still needs a **separate decision** for production rollout plus operational gate evidence.

**Next decision after spike:**

| Spike outcome | Committee action |
|---|---|
| All Q1–Q9 pass; Q10 needs ops evidence | Defer production; optionally adopt F3 behind startup flag pending SLO |
| Q1–Q3 fail | Reject rate-limit in agent; pursue collector/ops playbook (dossier family 2/3) |
| Q6/Q7 fail | Reject token bucket on hot path; revisit only if gate evidence appears |
| Q10 fails (insufficient protection) | Close rate-limit track; document ratio + tail sampling as sufficient |

---

## Appendix: Spike Questions Checklist

Use this checklist in the F3 Feasibility Report:

| Q | Question | Pass | Fail | Notes |
|---|---|---|---|---|
| Q1 | `policyEngine` remains `final`? | | | |
| Q2 | Disabled = exact 7-rule engine? | | | |
| Q3 | Enabled = 8-rule startup only? | | | |
| Q4 | Stateful rule as sole exception? | | | |
| Q5 | Concurrency tests pass? | | | |
| Q6 | Disabled overhead ≈ baseline? | | | |
| Q7 | Enabled within JMH budget? | | | |
| Q8 | Parent/force/QA semantics OK? | | | |
| Q9 | JMX counters sufficient? | | | |
| Q10 | Enough JVM protection to justify prod? | | | Committee + ops gate |

---

*End of F3 Technical Spike Plan.*
