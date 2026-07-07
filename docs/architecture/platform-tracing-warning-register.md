# Platform Tracing Warning Register

## Purpose

This file tracks **architecture, runtime, performance, and operational warnings** discovered during the migration to the clean agent-first tracing architecture (PR-6A through PR-9G and follow-ons).

Warnings listed here are **not automatically merge blockers**. Each entry must carry evidence, severity, status, and recommended handling so future PRs can decide:

- what must be fixed before pre-prod,
- what can be deferred,
- what is diagnostic-only,
- what was already resolved,
- what should **not** trigger premature optimization.

Update this register whenever a PR discovers, resolves, reclassifies, or explicitly accepts a warning.

**Source scope for this initial version:** workspace-visible docs, characterization reports, migration plan, SRE review, and PR close-out evidence (including PR-9G JMH recheck). Opus adversarial review blockers B1–B3 are included where corroborated by those sources.

---

## Warning policy

### Severity

| Level | Meaning |
|-------|---------|
| **BLOCKER** | Must be resolved before pre-prod or merge gate |
| **HIGH** | Important risk; usually address before pre-prod |
| **MEDIUM** | Track and schedule; not an immediate gate by itself |
| **LOW** | Cleanup, clarity, naming, documentation debt |
| **DIAGNOSTIC** | Useful signal; not a blocker unless macro evidence confirms |

### Status

| Status | Meaning |
|--------|---------|
| **OPEN** | Active warning |
| **IN_PROGRESS** | Targeted PR or work started |
| **RESOLVED** | Fixed with evidence |
| **ACCEPTED_RISK** | Known; explicitly accepted with mitigation |
| **DEFERRED** | Consciously postponed |
| **FALSE_POSITIVE** | Investigated; not a real issue |
| **SUPERSEDED** | Replaced by a newer entry or decision |

### Handling rules

1. **Do not optimize** from a single unstable microbenchmark unless reproduced (see W-011).
2. **Do not ignore hard gate failures** (`hardFAIL` on avgt/alloc); fix, reproduce, or explicitly classify with evidence.
3. **Do not mark RESOLVED** without test, benchmark, or doc evidence.
4. **Prefer targeted follow-up PRs** over broad rewrites.
5. **Keep entries factual and source-backed** — no speculative warnings.
6. **Do not convert this register into code changes** in the same PR that only creates/updates the register.
7. **Diagnostic-severity warnings** (e.g. W-011, W-012) are **not merge blockers by themselves** unless repeated macro evidence confirms an issue.

---

## Summary table

| ID | Severity | Status | Area | Warning | Source / Evidence | Recommended handling | Target PR |
|----|----------|--------|------|---------|-------------------|----------------------|-----------|
| W-001 | BLOCKER | RESOLVED | validation | Strict mode throws on business thread at span end; runtime strict enable now guarded | PR-9F `ValidationPolicyHolder` guard; `ValidationStrictRuntimeGuardTest` | Agent-side guard enforced; no `VALIDATION_REJECTED` added | PR-9F ✅ |
| W-002 | BLOCKER | RESOLVED | sampling | Overlapping route-ratio prefixes depended on map iteration order | Opus B2; pre-PR-9G [platform-tracing-sampling-characterization.md](platform-tracing-sampling-characterization.md) | Longest-prefix-wins at snapshot compile | PR-9G ✅ |
| W-003 | HIGH | RESOLVED | performance / validation | Full validation JMH reference baseline on windows-i5-13500-pr0 (PR-9H-B2) | PR-9H-B2 full 2×5×5 + gc.alloc.rate.norm; `jmhCompareBaseline` hardFAIL=0 | Macro/soak/RSS still required for pre-prod (W-004) | PR-9H-B2 ✅ |
| W-004 | HIGH | OPEN | performance / pre-prod | Macro / soak / RSS / release perf gates incomplete | PR-9H-E2L Gentoo local K8s lab scaffolding | Official SRE/pre-prod REFERENCE + soak (PR-9H-E2-run/F); W-004 resolution PR-9H-G | PR-9H-E2L partial ✅ / PR-12 |
| W-005 | MEDIUM | OPEN | scrubbing / operator | Actuator/JMX expose `liveRuleCount` but not active rule names | [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md) follow-up #2 | Optional read getter for operator diagnostics | PR-9I |
| W-006 | MEDIUM | OPEN | scrubbing / operator | Unknown scrubbing rule names skipped silently at agent; skipped list not in read model | [platform-tracing-scrubbing-validation-characterization.md](platform-tracing-scrubbing-validation-characterization.md) PR-9D `ScrubbingRuleResolutionResult.skippedUnknownNames`; actuator shows count only | Expose skipped-unknown diagnostics if safe | PR-9I |
| W-007 | HIGH | OPEN | operations / security | Actuator mutation guarded (PR-9J); JMX deployment access policy remains | PR-9J `TracingActuatorEndpoint` guard; migration plan Risk 11 | Actuator default-off; JMX restricted by deployment/JVM/platform | PR-9J partial ✅ / PR-11 |
| W-008 | MEDIUM | ACCEPTED_RISK | scrubbing / performance | Pathological regex (ReDoS) mitigated by circuit breaker, not eliminated | [platform-tracing-scrubbing-validation-characterization.md](platform-tracing-scrubbing-validation-characterization.md) PR-7B; [ADR-platform-tracing-clean-core-hybrid.md](../decisions/ADR-platform-tracing-clean-core-hybrid.md) | Operator runbook; future analyzer only if evidence requires | — |
| W-009 | LOW | DEFERRED | naming | `SamplingControlClient` is unified multi-domain client; name is historical | [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md) follow-up #1; [platform-tracing-core-extraction-readiness.md](platform-tracing-core-extraction-readiness.md) | Mechanical rename when reference surface allows | PR-9A (deferred) |
| W-010 | LOW | DEFERRED | read model | Legacy `config.lastUpdatedSource` is sampling-only | [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md); [runtime-sampling-control.md](../tracing/runtime-sampling-control.md) | Prefer per-domain `configSource`; remove when consumers migrate | — |
| W-011 | DIAGNOSTIC | OPEN | performance / sampling | `dropPath` JMH avgt unstable; full run hardFAIL, focused recheck hardWARN | PR-9G close-out: full run +32.5% hardFAIL; focused recheck +15.8% hardWARN, hardFAIL=0; baseline bimodal fork rawData | Track in perf evidence phase; do not block unrelated PRs | PR-9H |
| W-012 | DIAGNOSTIC | OPEN | performance diagnostics | Sample-mode JMH can fail while avgt/alloc hard gate passes | [platform-tracing-sampling-characterization.md](platform-tracing-sampling-characterization.md) PR-6C3; [jmh-suite.md](../tracing/jmh-suite.md) | Diagnostic by default; optional `-PjmhFailOnSampleRegression=true` | — |
| W-013 | HIGH | OPEN | validation / operations | Startup `validation.strict=true` deploy risk — ops policy + startup WARN added (PR-9J) | PR-9J startup WARN; PR-9F guards runtime only | Production: `strict=false`; startup strict for CI/test only | PR-9J partial ✅ |

---

## Detailed warnings

### W-001 — Validation strict-mode safety

- **Severity:** BLOCKER (historical)
- **Status:** RESOLVED
- **Area:** validation / runtime policy
- **First seen:** PR-8A validation runtime foundation; Opus adversarial review B1
- **Source / evidence:**
  - [platform-tracing-scrubbing-validation-characterization.md](platform-tracing-scrubbing-validation-characterization.md): strict mode throws `TracingValidationException`, blocks span end (VAL-STRICT-MISS).
  - [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md): validation degradation is **not** dropped-span loss — no `VALIDATION_REJECTED` in taxonomy.
  - PR-9F: `ValidationStrictRuntimeGuardTest`, `ValidationPolicyHolder.tryApplyPolicyUpdate` guard.
- **Description:** `validation.strict=true` causes `ValidatingSpanProcessor.onEnding` to throw on the business thread during `Span.end()` when required attributes are missing. Strict mode is a **CI/test/pre-prod diagnostic mode**, not a safe default production runtime policy while throws-from-`Span.end()` remain possible.
- **Why it matters:** Runtime enablement of strict mode can break request completion paths; observability does not classify this as span export loss.
- **Recommended handling:** Resolved — agent-side runtime guard rejects `strict=true` updates unless `platform.tracing.validation.strict-runtime-allowed=true` at startup.
- **Target PR / milestone:** PR-9F ✅
- **Resolution notes:** PR-9F added agent-side runtime strict-mode guard in `ValidationPolicyHolder`. Runtime strict enable rejected by default. LKG remains active; version/source unchanged on rejection; `InvalidConfigCount` increments. `liveStrict` stays false. No `VALIDATION_REJECTED` added. Startup `validation.strict=true` unchanged for CI/test diagnostics.

### W-002 — Route-ratio nondeterminism (overlapping prefixes)

- **Severity:** BLOCKER (historical)
- **Status:** RESOLVED
- **Area:** sampling
- **First seen:** Opus adversarial review B2; pre-PR-9G docs (HashMap iteration order)
- **Source / evidence:**
  - Pre-PR-9G [platform-tracing-sampling-characterization.md](platform-tracing-sampling-characterization.md) priority implications.
  - PR-9G: `SamplingPolicySnapshot.normalizeRouteRatios()` — length descending, lexicographic ascending tie-breaker.
- **Description:** Overlapping route-ratio prefixes previously matched in HashMap/insertion-dependent order; behavior was nondeterministic across JVMs and config order.
- **Why it matters:** Operators with overlapping prefixes (e.g. `/api` vs `/api/v2`) could see unpredictable sampling rates.
- **Recommended handling:** Resolved — longest-prefix-wins at snapshot compile; no hot-path sort.
- **Target PR / milestone:** PR-9G ✅
- **Resolution notes:** `RouteRatioPolicyRule` unchanged (first-match over sorted array). Runtime JMX/Spring updates use same normalization. Operators with overlapping prefixes should review configs post-upgrade.

### W-003 — Validation performance evidence missing

- **Severity:** HIGH
- **Status:** RESOLVED
- **Area:** performance / validation
- **First seen:** Opus adversarial review B3 (partial)
- **Source / evidence:**
  - PR-9H-A: `ValidatingSpanProcessorBenchmark` in `platform-tracing-bench` (6 hard-gate + 1 diagnostic).
  - PR-9H-B: gate wiring, artifact hygiene, provisional jmhDev merge.
  - PR-9H-B2: [PR-9H-validation-jmh.md](../perf-evidence/PR-9H-validation-jmh.md) — full 2×5×5 reference run on `windows-i5-13500-pr0` with GC profiler; 6 hard-gate avgt + `·gc.alloc.rate.norm` in committed baseline; `jmhCompareBaseline` matched 6/6, hardFAIL=0.
- **Description:** Validation policy hot path lacked dedicated JMH baseline and compare gate coverage comparable to sampling/scrubbing.
- **Why it mattered:** Extraction and runtime policy changes to validation could not be perf-gated independently until baseline/gate wired.
- **Recommended handling:** PR-9H-C — macro/soak/RSS (W-004) for pre-prod sign-off.
- **Target PR / milestone:** PR-9H-B2 ✅
- **Resolution notes:** PR-9H-B2 replaced provisional jmhDev validation entries with full reference-profile capture (2 forks × 5 warmup × 5 measurement, GC profiler). Baseline at `baselines/windows-i5-13500-pr0/results.json`; diagnostic `validationStrictAllowedMissingAttrDiagnostic` excluded from hard avgt gate. JMH evidence alone does not prove pre-prod readiness (W-004 remains OPEN).

### W-004 — Macro / soak / RSS / release perf gates incomplete

- **Severity:** HIGH
- **Status:** OPEN
- **Area:** performance / pre-prod
- **First seen:** PR-0 baseline; SRE review 2026-06-10
- **Source / evidence:**
  - [sre-requirements-coverage-review.md](../tracing/sre-requirements-coverage-review.md): release performance gates **FAIL/PENDING** (§6).
  - [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md): documented M5 FAIL (+48% CPU vs M0, +25% RSS vs M0); M0/M5/M10 mandatory before committee sign-off.
  - PR-9H-C: `platform-tracing-perf-harness` module — hybrid SMOKE/REFERENCE tiers; perf app + k6 skeletons + scenario profiles S0–S6.
  - PR-9H-D: first macro SMOKE execution support (local k6, Docker local/remote); refined scenario matrix; tier-stamped artifacts — **still non-authoritative**.
  - PR-9H-E: Kubernetes reference-run templates, reference evidence contract, W-004 checklist, guarded `perfReferenceRun` — **no official reference execution**.
  - PR-9H-E1: hardened REFERENCE evidence contract — schema eligibility guards, CPU/RPS normalization, CFS throttling validity, k6 dropped-iteration rules, working-set source semantics, JFR startup/parity metadata, collector backpressure template, budgets/variance structure — **no official reference execution**.
  - PR-9H-E2: first official Kubernetes REFERENCE run scaffolding — k6 scenarios ConfigMap generation, artifact hygiene, evidence/reference conventions, E2 pre-flight checklist, runbook, summary skeleton task — **actual cluster execution pending environment prerequisites**.
  - PR-9H-E2L: Gentoo local Kubernetes reference-lab scaffolding — SSH/Docker remote inventory, kind lab scripts, `labTier=LOCAL_REFERENCE_LAB` schema guards, `evidence/reference-local/` — **local K8s run pending SSH approval**.
  - PR-9H-E2L-run (2026-06-14): Gentoo kind lab executed S0/S1/S4 at TARGET_RPS=20; k6 load/latency evidence under `evidence/reference-local/gentoo-local-reference-lab-v1/20260614-local-1/` — **non-authoritative; W-004 OPEN**.
  - PR-9H-E2L-METRICS (2026-06-14): local diagnostics for Prometheus/cAdvisor/JFR — root cause: **Prometheus not installed** (no cAdvisor `container_*`); collector `otelcol_*` available at pod IP :8888; JFR not configured. Scaffolding + collector compact summary. **No official REFERENCE evidence.**
  - PR-9H-E2L-JFR-fix (2026-06-15): fixed local Gentoo JFR capture (`disk=true`, repository under `/tmp/jfr`, largest-`.jfr` copy without jcmd); reran S0/S1/S4 in `20260614-local-jfr-fix-1` with non-empty JFR (1.9–2.7 MB). `jfrDetailedGcSummary` still missing. Evidence non-authoritative; W-004 **OPEN**.
  - PR-9H-E2L-JFR-summary (2026-06-15): parsed fix-1 artifacts; all **locked stream state**; parser scaffolding only. W-004 **OPEN**.
  - PR-9H-E2L-JFR-finalize (2026-06-15): fixed local JFR finalization before copy using duration-bound startup recording (`duration=2m` smoke / `7m` scenarios) and produced parseable LOCAL_REFERENCE_LAB JFR artifacts for S0/S1/S4 with GC pause summaries in `20260614-local-jfr-finalize-1`. Evidence remains non-authoritative because labTier=LOCAL_REFERENCE_LAB, localEnvironment, singleRunOnly, and provisionalBudgetOnly. Official SRE/pre-prod REFERENCE run, reproducibility run, S6 soak/RSS trend, and SRE-approved budgets remain required. W-004 **OPEN**.
- **Description:** Pre-prod sign-off requires macro-load evidence under production-like limits, not microbenchmarks, local smoke, or local Gentoo lab runs.
- **Why it matters:** Micro JMH improvements, SMOKE tier, and LOCAL_REFERENCE_LAB do not prove CPU/RSS/p99 budgets under production-like load.
- **Recommended handling:** PR-9H-E2L-run — local Gentoo lab after approval; PR-9H-E2-run — official SRE/pre-prod REFERENCE; PR-9H-F — soak; PR-9H-G — W-004 decision.
- **Target PR / milestone:** PR-9H-E2L partial ✅ / PR-9H-E2-run / PR-12
- **Resolution notes:** PR-9H-E2L added Gentoo local reference-lab scripts. PR-9H-E2L-run: k6-only (20260614-local-1). PR-9H-E2L-METRICS: diagnostics. PR-9H-E2L-FULL: Prometheus + k6/Prom/collector (20260614-local-metrics-full-1; JFR empty). PR-9H-E2L-JFR-fix: JFR capture fixed + S0/S1/S4 rerun (20260614-local-jfr-fix-1; locked stream). PR-9H-E2L-JFR-summary: parser attempt on fix-1. PR-9H-E2L-JFR-finalize: duration-bound JFR + parseable GC summaries (20260614-local-jfr-finalize-1). **W-004 remains OPEN**.

### W-005 — Scrubbing live rule names not exposed

- **Severity:** MEDIUM
- **Status:** OPEN
- **Area:** scrubbing / operator diagnostics
- **First seen:** PR-8D read model
- **Source / evidence:**
  - [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md) follow-up #2.
  - [runtime-sampling-control.md](../tracing/runtime-sampling-control.md) PR-8D read model: scrubbing shows `liveRuleCount` only.
- **Description:** Operators cannot see which built-in scrubbing rules are active at runtime via actuator/JMX read model.
- **Why it matters:** Incident response and config drift checks require inferring policy from count alone.
- **Recommended handling:** PR-9I — optional name-level read getter if payload size and security review allow.
- **Target PR / milestone:** PR-9I
- **Resolution notes:** —

### W-006 — Unknown scrubbing rule names not visible to operators

- **Severity:** MEDIUM
- **Status:** OPEN
- **Area:** scrubbing / operator diagnostics
- **First seen:** PR-7B / PR-9D
- **Source / evidence:**
  - [platform-tracing-scrubbing-validation-characterization.md](platform-tracing-scrubbing-validation-characterization.md): unknown names skipped (startup parity); `ScrubbingRuleResolutionResult` carries `skippedUnknownNames`.
  - Actuator read model exposes configured `builtInRules` and `liveRuleCount`, not skipped-unknown list.
- **Description:** Invalid rule names in runtime config are skipped during agent compile; operators may believe rules are active when they were silently dropped.
- **Why it matters:** Misconfiguration can leave PII unscrubbed without a clear operator-facing signal.
- **Recommended handling:** PR-9I — expose skipped-unknown names in diagnostics/audit if safe; until then document in runbook.
- **Target PR / milestone:** PR-9I
- **Resolution notes:** —

### W-007 — JMX / Actuator access hardening

- **Severity:** HIGH
- **Status:** OPEN (Actuator partial; JMX deployment policy remains)
- **Area:** operations / security
- **First seen:** Migration plan Risk 11; inventory MIGRATION_RISK
- **Source / evidence:**
  - [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md) Risk 11: `TracingActuatorEndpoint.WriteOperation` without prod disable guard.
  - Same doc §12.3 Q15–Q16: READ/WRITE exposure, role-based security, restricted management port.
  - PR-9J: `platform.tracing.actuator.mutation-enabled=false` default; HTTP 403 on write; read unchanged.
- **Description:** Runtime policy write operations are reachable via JMX and Actuator. PR-9J guards Actuator HTTP mutation by default; direct JMX remains a separate control surface requiring deployment-level security.
- **Why it matters:** Unauthorized mutation can drop all traces, disable scrubbing, or enable strict validation — high blast radius. Actuator guard alone is insufficient if JMX is exposed.
- **Recommended handling:** Production: `platform.tracing.actuator.mutation-enabled=false`. Restrict JMX via JVM config, firewall, management port, platform RBAC. PR-11 may add E2E prod-profile tests.
- **Target PR / milestone:** PR-9J partial ✅ / PR-11
- **Resolution notes:** PR-9J added Actuator mutation guard (`TracingActuatorEndpoint.assertMutationAllowed`, `TracingProperties.Actuator.mutationEnabled` default false). Read model exposes `actuator.mutationEnabled`. Direct JMX not code-guarded — Spring `RuntimeConfigApplier` requires JMX path; documented in [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md).

### W-008 — ReDoS risk in scrubbing regex

- **Severity:** MEDIUM
- **Status:** ACCEPTED_RISK
- **Area:** scrubbing / performance / operations
- **First seen:** PR-7B scrubbing runtime
- **Source / evidence:**
  - [platform-tracing-scrubbing-validation-characterization.md](platform-tracing-scrubbing-validation-characterization.md) PR-7B: "Catastrophic ReDoS not solved here; per-rule `RuleCircuitBreaker` + rate-limited logging remain active."
  - [ADR-platform-tracing-clean-core-hybrid.md](../decisions/ADR-platform-tracing-clean-core-hybrid.md): validate at `tryUpdate`, circuit breaker at runtime.
- **Description:** Syntactically valid but pathological regex can still be expensive until the circuit breaker trips.
- **Why it matters:** Custom/SPI rules or future dynamic regex could cause latency spikes.
- **Recommended handling:** Document operator runbook (rule change procedure, monitor circuit-breaker metrics); add static analyzer only if production evidence requires it. Do not block migration on theoretical ReDoS elimination.
- **Target PR / milestone:** —
- **Resolution notes:** `ScrubbingSecurityNegativeTest` and circuit breaker remain mandatory protections.

### W-009 — SamplingControlClient historical name

- **Severity:** LOW
- **Status:** DEFERRED
- **Area:** naming / maintainability
- **First seen:** PR-9A unified architecture
- **Source / evidence:**
  - [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md) follow-up #1.
  - [platform-tracing-core-extraction-readiness.md](platform-tracing-core-extraction-readiness.md) §7 deferred rename.
- **Description:** `SamplingControlClient` now drives sampling, scrubbing, and validation JMX updates; class name reflects sampling-only history.
- **Why it matters:** Onboarding friction and incorrect assumptions about scope.
- **Recommended handling:** Deferred mechanical rename to `PlatformTracingControlClient` with optional `@Deprecated` alias period — wide reference surface.
- **Target PR / milestone:** PR-9A (deferred)
- **Resolution notes:** —

### W-010 — Legacy global config.lastUpdatedSource

- **Severity:** LOW
- **Status:** DEFERRED
- **Area:** read model / compatibility
- **First seen:** PR-8D aggregated actuator
- **Source / evidence:**
  - [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md): legacy caveat.
  - [runtime-sampling-control.md](../tracing/runtime-sampling-control.md): prefer per-domain `configSource`.
- **Description:** Top-level actuator field `config.lastUpdatedSource` reflects **sampling only**; scrubbing and validation have separate version/source fields.
- **Why it matters:** Operators reading legacy field may misattribute last change across domains.
- **Recommended handling:** Keep until consumers migrate; document per-domain fields as authoritative.
- **Target PR / milestone:** —
- **Resolution notes:** —

### W-011 — dropPath JMH instability

- **Severity:** DIAGNOSTIC
- **Status:** OPEN
- **Area:** performance / sampling
- **First seen:** PR-9G full CompositeSampler JMH compare
- **Source / evidence:**
  - PR-9G close-out: full `jmhCompareBaseline` hardFAIL on `CompositeSamplerPolicyBranchesBenchmark.dropPath` avgt +32.5%, alloc +0.0%.
  - Focused recheck (PowerShell quoted `-PjmhInclude`): hardFAIL=0, hardWARN +15.8% avgt; baseline `results.json` shows bimodal fork rawData (~42 ns vs ~84 ns).
  - PR-9G changed route-ratio compile only; `dropPath` exercises hard-drop path.
- **Description:** `dropPath` microbenchmark shows high run-to-run variance; full-suite compare can hard-fail without implying a code regression.
- **Why it matters:** False-positive gate failures can block unrelated PRs if not classified.
- **Recommended handling:** Reproduce before optimizing; investigate baseline fork stability under PR-9H perf evidence work. **Not** a PR-9G blocker.
- **Target PR / milestone:** PR-9H
- **Resolution notes:** Route-ratio benchmarks improved in same full run (-20% to -27% avgt).

### W-012 — Sample-mode JMH diag FAILs (non-blocking)

- **Severity:** DIAGNOSTIC
- **Status:** OPEN
- **Area:** performance diagnostics
- **First seen:** PR-6C3 gate policy
- **Source / evidence:**
  - [platform-tracing-sampling-characterization.md](platform-tracing-sampling-characterization.md) PR-6C3.
  - [jmh-suite.md](../tracing/jmh-suite.md) gate policy table.
  - PR-9G full run: 7 diagFAIL on sample-mode route-ratio cases; avgt/alloc hard gate passed for route-ratio.
- **Description:** JMH sample-mode (p99-style) latency can regress while avgt and `gc.alloc.rate.norm` remain within hard gate thresholds.
- **Why it matters:** Tail latency concerns require macro HDR/JFR evidence, not sample-mode microbench alone.
- **Recommended handling:** Report diagFAILs; do not block merge unless repeated macro evidence confirms. Optional strict gate: `-PjmhFailOnSampleRegression=true`.
- **Target PR / milestone:** —
- **Resolution notes:** —

### W-013 — Startup validation strict-mode production safety

- **Severity:** HIGH
- **Status:** OPEN (ops policy + startup WARN; deploy-config risk remains by design)
- **Area:** validation / operations
- **First seen:** Opus delta-review after PR-9F / PR-9G
- **Source / evidence:**
  - PR-9F guards runtime strict enable in `ValidationPolicyHolder.tryApplyPolicyUpdate()`.
  - Startup `platform.tracing.validation.strict=true` remains honored at processor construction.
  - Strict mode can throw `TracingValidationException` from `Span.end()` on the business thread.
  - `strictRuntimeAllowed` is agent startup configuration; Spring binding may be informational unless wired into agent config.
  - PR-9J: one-time startup WARN in `ValidatingSpanProcessor` constructor; ops docs updated.
- **Description:** PR-9F rejects runtime attempts to enable strict mode by default, but startup strict mode is still possible through static deploy config. In production, `startup strict=true` can reintroduce the same business-thread exception risk that PR-9F removed from the runtime update path. W-001 runtime path is resolved; W-013 is the residual startup/deploy-config risk.
- **Why it matters:** Operators may assume PR-9F makes strict mode safe in all cases, while startup `strict=true` can still throw from `Span.end()`. Configured vs live `strictRuntimeAllowed` drift can also confuse operators if Spring config differs from agent startup config.
- **Recommended handling:** Production must use `validation.strict=false`, `validation.strict-runtime-allowed=false`. Startup strict for CI/test/pre-prod only. Rely on `liveStrictRuntimeAllowed` in read model for agent enforcement. Do not add `VALIDATION_REJECTED` for this warning.
- **Target PR / milestone:** PR-9J partial ✅
- **Resolution notes:** PR-9J added startup WARN, documented agent-vs-Spring `strictRuntimeAllowed` drift on actuator read model (`strictRuntimeAllowedNote`), and ops policy in characterization docs. Startup strict behavior unchanged — intentional for diagnostics. Not marked RESOLVED: deploy-time `strict=true` remains possible.

---

## Resolved warnings

| ID | Title | Resolved by | Evidence |
|----|-------|-------------|----------|
| W-001 | Validation strict-mode safety | PR-9F | Agent-side `strictRuntimeAllowed` guard; `ValidationStrictRuntimeGuardTest`; docs updated |
| W-002 | Route-ratio nondeterminism | PR-9G | Deterministic `normalizeRouteRatios()`; characterization + JMH route-ratio improved; docs updated |
| W-003 | Validation performance evidence | PR-9H-B2 | Full 2×5×5 validation JMH baseline + alloc gate on `windows-i5-13500-pr0`; [PR-9H-validation-jmh.md](../perf-evidence/PR-9H-validation-jmh.md) |

---

## Update rules for future PRs

Future PRs **must update this file** when:

- a new blocker or HIGH warning is found (architecture review, ops incident, security review),
- a JMH `hardFAIL` or sustained `hardWARN` appears,
- a warning is fixed, accepted as risk, or reclassified,
- a deferred decision becomes active work,
- operator, security, or performance risk materially changes.

A PR that touches **runtime policy**, **performance gates**, **operator diagnostics**, **security posture**, or **architecture boundaries** must explicitly mention this register in its final report.

### Mandatory close-out checklist (migration PRs after PR-9G)

Each future PR close-out report **must** include:

- **Warnings added:** (new IDs, or `none`)
- **Warnings resolved:** (IDs + evidence, or `none`)
- **Warnings reclassified:** (ID, old → new severity/status + rationale, or `none`)
- **Warnings intentionally unchanged:** (explicit no-op, or `none`)
- **Warning register updated:** `yes` / `no`
- **If no:** explain why no warning register change was needed

Updating this Markdown file is part of close-out when any of the trigger conditions above apply. A PR that only resolves or reclassifies warnings must still set **Warning register updated: yes**.

Do **not** remove RESOLVED entries from the summary without moving them to the Resolved section for audit trail.

---

## References

- [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md)
- [platform-tracing-sampling-characterization.md](platform-tracing-sampling-characterization.md)
- [platform-tracing-scrubbing-validation-characterization.md](platform-tracing-scrubbing-validation-characterization.md)
- [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md)
- [sre-requirements-coverage-review.md](../tracing/sre-requirements-coverage-review.md)
