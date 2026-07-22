# Slice K: CP-3 / CP-4 Decision Evidence

> Date: 2026-07-22  
> Branch: `feature/slice-k-optional-verification`  
> Base: `ca78567e57fa454b5e44a0959de974afd4fbc270` (merge PR #22 / Slice J)  
> Audit type: optional architecture checkpoint — verification only

## 1. Prerequisites

| Check | Result |
|---|---|
| Worktree clean before audit | PASS |
| Branch | `feature/slice-k-optional-verification` |
| Base equals current `master` | PASS (`ca78567`) |
| Slice G merge present | PASS (`efd7cc3` / PR #21) |
| Slice I merge present | PASS (`281acda` / PR #20) |
| Slice J merge present | PASS (`ca78567` / PR #22) |
| `platform-tracing-core` directory absent | PASS |
| `platform-tracing-otel-extension` directory absent | PASS |
| `platform-tracing-otel` present | PASS |
| `platform-tracing-otel-javaagent-extension` present | PASS |
| Agent extension package `otel.javaagent` | PASS |
| CP-1 / CP-2 decisions intact | PASS |

## 2. CP-3 — Package identity (`core.*` inside `platform-tracing-otel`)

### 2.1 Inventory

| Metric | Count |
|---|---:|
| Main Java files under `space/br1440/platform/tracing/core` | 92 |
| Test Java files under `core` | 77 |
| Total `core.*` package declarations | 169 |
| Java files referencing `core` string | 271 |
| Java files with `import …core…` | 175 |
| Hardcoded `core.*` FQNs in ArchUnit / fitness rules | 20+ |
| ABI snapshot public core types | 65 |
| Combined ABI lock lines (`platform-tracing-api-otel.txt`) | 905 |
| Production `Class.forName("…tracing.core…")` | 0 |
| `META-INF/services` descriptors referencing `core.*` | 0 |
| JMX `ObjectName` embedding `core.*` FQNs | 0 |
| Split-package across modules | 0 (single JAR owner) |

### 2.2 Classification of `core.*` uses

| Category | Finding |
|---|---|
| Public API exposure | API main has **0** compile imports of `core.*` |
| Internal implementation | All 169 declarations live in `platform-tracing-otel` |
| Spring bean wiring | Autoconfigure wires core types at composition root (expected) |
| Reflection / FQN contract | Test/gate reflection only (`AbiSnapshotTest`, contract tests) |
| SPI descriptor | None for sampling/policy (`SamplingPolicyInternalContractTest` verifies absence) |
| JMX / wire contract | Domain `space.br1440.platform.tracing:type=…`; no core FQN in wire |
| Published consumer surface | C3 fixture blocks compile-time otel/core leak; negative probe documents misconfig |
| External consumer compile | Starters/web autoconfigure main: **0** `core.*` imports (ArchUnit enforced) |

### 2.3 Residual findings (not rename justification)

| ID | Finding | Rename required? |
|---|---|---|
| D1 | Public `OtelTraceparentReader` interface in `core.propagation` (otel JAR) | No — visibility/ownership issue solvable without mass rename |
| D2 | Autoconfigure compile-depends on wide core surface | No — composition-root pattern; C3 gate enforces consumer classpath |
| D3 | ArchUnit predicate drift `sampling.config` vs `sampling.properties` | No — rule maintenance, not package ambiguity |
| D4 | ABI lock couples API + core public surface | No — consequence of rename cost, not runtime defect |
| D5 | `ProductionSamplingPolicyChain` public for cross-package engine access | No — sealed by ArchUnit (`PRODUCTION_CHAIN_ACCESS_RESTRICTED`) |

Module name (`platform-tracing-otel`) vs package name (`core.*`) mismatch is **documented intentional**
post-Slice J — artifact boundary vs internal semantic taxonomy.

### 2.4 Five mandatory rename criteria

| Criterion | Proven? | Evidence |
|---|---|---|
| 1. Concrete ambiguity / wrong dependency edge | **NO** | No classloader collision, no SPI ambiguity, no misleading public FQN in API module |
| 2. Exact enforcement defect unsolvable while keeping package | **NO** | `ModuleTaxonomyArchRules`, Slice I synthetic negatives, C3 published-consumer, ABI snapshots |
| 3. Existing taxonomy cannot solve it | **NO** | ArchUnit + visibility + docs suffice |
| 4. Material architectural benefit exceeding naming | **NO** | Rename cost >> benefit; no demonstrated operational confusion |
| 5. Bounded migration plan | **NO** | Blast radius: 169 declarations, 175 import sites, 20+ ArchUnit FQNs, dual ABI snapshots |

**Rename falsification attempt: FAILED.** Defaults hold.

### 2.5 Alternatives scoring (weights: dependency clarity 25%, public API safety 20%, classloader safety 15%, migration risk 20%, operational value 10%, maintainability 10%)

| Alternative | Weighted score (/10) | Notes |
|---|---:|---|
| **A — KEEP `core.*`** | **9.1** | Zero churn; gates enforce boundary |
| B — Rename all to `otel.*` | 3.2 | Large ABI break; no proven defect cured |
| C — Partial rename | 2.0 | Mixed taxonomy — strongly disfavored |
| D — Compatibility aliases | 1.5 | Pre-production policy forbids |
| E — Split neutral vs OTel packages | 2.8 | Recreates rejected otel-runtime topology |

Sensitivity: doubling "semantic honesty" weight still leaves **A** ahead.

### 2.6 CP-3 verdict

```text
CP-3 APPROVED — KEEP space.br1440.platform.tracing.core.*
```

- Module name describes the technology-specific artifact boundary.
- `core.*` is an internal semantic taxonomy, not a promise of a reusable neutral core module.
- Application consumers must use `platform-tracing-api`; implementation packages require explicit dependency.
- Architecture gates — not package-name cosmetics — enforce the boundary.
- Future rename requires new falsifying evidence and a dedicated migration slice.

## 3. CP-4 — Enrichment contract (`void` vs `EnrichmentOutcome`)

### 3.1 Caller inventory

| Location | Role | Branches on result? |
|---|---|---|
| `DefaultSpanEnricher` | Implementation | N/A |
| `SemanticLayerAutoConfiguration` | Registers `@Bean SpanEnricher` | N/A |
| Unit / characterization tests | Verification | No |
| **Production callers of `SpanEnricher.enrichCurrentSpan`** | — | **0** |

Parallel void enrichment paths exist (`TracedAspect`, response-header filters, `ExceptionRecorder`, agent processors) — all command-style, no result branching.

`EnrichmentOutcome` type: **does not exist** in repository.

### 3.2 Defect analysis

| Question | Answer |
|---|---|
| Callers required to branch on enrichment result? | **No** — zero production callers |
| Partial success observable and actionable today? | Attribute limits/scrubbing handled at processor/metrics layer |
| Does `void` hide security-relevant rejection? | **No** — closed 3-method surface; export-time scrubbing via `ScrubbingDecision` SPI |
| Real consumer needing typed outcome? | **None** |
| Internal diagnostics sufficient? | **Yes** — `MetricsSpanProcessor`, actuator limits, processor-layer scrubbing |

Documented API-05 (silent no-op on invalid/non-recording span) is an unresolved observability question, not a proven contract defect requiring public API change.

### 3.3 Alternatives scoring (weights: caller actionability 25%, public API stability 20%, implementation complexity 15%, security visibility 15%, testability 10%, misuse risk 15%)

| Alternative | Weighted score (/10) | Notes |
|---|---:|---|
| **A — KEEP `void`** | **9.3** | Matches peer patterns; zero consumers |
| B — Public `EnrichmentOutcome` | 2.8 | Permanent API without consumer |
| C — Internal result type only | 4.5 | Adds complexity without proven need |
| D — Diagnostics/metrics only | 8.0 | Already partially present |
| E — Exception-based failure | 3.5 | Inconsistent with command-style enrichment |

### 3.4 CP-4 verdict

```text
CP-4 APPROVED — KEEP void enrichment contract
DO NOT introduce EnrichmentOutcome
```

- Enrichment remains command-style.
- Callers do not branch on enrichment result.
- Failures/diagnostics remain governed by existing processor/metrics contracts.
- Typed result reconsideration requires a demonstrated actionable consumer.

## 4. Slice K outcome

```text
Outcome A — CONFIRMED
CP-3 CLOSED — KEEP core.*
CP-4 CLOSED — KEEP void
SLICE K CLOSED — NO-OP / VERIFICATION ONLY
```

No production Java, Gradle metadata, ABI, package moves, or distribution changes authorized or performed.
