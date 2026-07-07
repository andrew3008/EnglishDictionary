# JMH-BASELINE-FINALIZATION — official baseline evidence task

Date: 2026-06-12  
Task: `JMH-BASELINE-FINALIZATION`  
Scope: benchmark harness / Gradle / docs only — **no production code changes**

## Status

**`JMH_BASELINE_STILL_BLOCKED`**

| Item | Result |
|------|--------|
| Expected benchmark classes (source) | **15** |
| Valid batch JSON captured | **2 / 15** |
| Batch manifest (`manifest.json`) | **NOT FOUND** (written only when `jmhRunBaselineBatches` completes) |
| `validated-manifest.json` | **FAIL** (2 present, 12 missing, 1 failed/in-progress) |
| Merged `results.json` | **NOT CREATED** |
| `jmhSaveBaseline` | **NOT RUN** |
| Saved baseline (`baselines/<profileId>/`) | **NOT FOUND** |
| Background batch run | **IN_PROGRESS** (class 3/15 active; not stalled) |

## Baseline path selected

**Batched path (`FULL_BASELINE_BATCHED`)** — selected because single full `./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g` ETA ~4h is impractical for an interactive Composer session.

Single-run path was **not attempted** in this session.

Current batch state: **IN_PROGRESS** — background `jmhRunBaselineBatches` (pid 27012, started 2026-06-12 ~16:47 MSK) completed classes 1–2 and is executing class 3 (`CompositeSamplerBenchmark`). Three bounded progress polls (90s apart) showed active JMH output but no new completed batch JSON; `CompositeSamplerBenchmark.json` remains 0 bytes until JMH finishes the class.

## Expected benchmark inventory

Discovered from `platform-tracing-bench/src/jmh/java/**/*Benchmark.java` (15 classes):

| # | benchmarkClass | sourcePath | expectedInBatchManifest | resultFile | batchStatus |
|---|----------------|------------|-------------------------|------------|-------------|
| 1 | `space.br1440.platform.tracing.bench.AttributePolicyBenchmark` | `.../AttributePolicyBenchmark.java` | true | `batches/AttributePolicyBenchmark.json` | **PASS** (21286 B, 3 results) |
| 2 | `space.br1440.platform.tracing.bench.CompositePipelineBenchmark` | `.../CompositePipelineBenchmark.java` | true | `batches/CompositePipelineBenchmark.json` | **PASS** (28431 B, 4 results) |
| 3 | `space.br1440.platform.tracing.bench.CompositeSamplerBenchmark` | `.../CompositeSamplerBenchmark.java` | true | `batches/CompositeSamplerBenchmark.json` | **IN_PROGRESS** (0 B) |
| 4 | `space.br1440.platform.tracing.bench.CompositeSamplerConcurrentUpdateBenchmark` | `.../CompositeSamplerConcurrentUpdateBenchmark.java` | true | — | **MISSING** |
| 5 | `space.br1440.platform.tracing.bench.CompositeSamplerPolicyBranchesBenchmark` | `.../CompositeSamplerPolicyBranchesBenchmark.java` | true | — | **MISSING** |
| 6 | `space.br1440.platform.tracing.bench.ContextScopeBenchmark` | `.../ContextScopeBenchmark.java` | true | — | **MISSING** |
| 7 | `space.br1440.platform.tracing.bench.HeaderPropagationBenchmark` | `.../HeaderPropagationBenchmark.java` | true | — | **MISSING** |
| 8 | `space.br1440.platform.tracing.bench.MdcCorrelationBenchmark` | `.../MdcCorrelationBenchmark.java` | true | — | **MISSING** |
| 9 | `space.br1440.platform.tracing.bench.QueueOfferBenchmark` | `.../QueueOfferBenchmark.java` | true | — | **MISSING** |
| 10 | `space.br1440.platform.tracing.bench.ScrubbingEngineBenchmark` | `.../ScrubbingEngineBenchmark.java` | true | — | **MISSING** |
| 11 | `space.br1440.platform.tracing.bench.ScrubbingPerRuleBenchmark` | `.../ScrubbingPerRuleBenchmark.java` | true | — | **MISSING** |
| 12 | `space.br1440.platform.tracing.bench.SpanLimitsBenchmark` | `.../SpanLimitsBenchmark.java` | true | — | **MISSING** |
| 13 | `space.br1440.platform.tracing.bench.StartSpanBenchmark` | `.../StartSpanBenchmark.java` | true | — | **MISSING** |
| 14 | `space.br1440.platform.tracing.bench.TracedAspectBenchmark` | `.../TracedAspectBenchmark.java` | true | — | **MISSING** |
| 15 | `space.br1440.platform.tracing.bench.TypedBuilderBenchmark` | `.../TypedBuilderBenchmark.java` | true | — | **MISSING** |

Source count **15** matches prior documentation; no discrepancy.

## Batch manifest validation

| Check | Result |
|-------|--------|
| `manifest.json` exists | **NO** — batch task still running |
| `overallStatus == PASS` | **N/A** |
| All expected classes present | **NO** (2/15) |
| Failed batch entries | **1 in-progress** (`CompositeSamplerBenchmark`, 0-byte JSON) |
| Empty / zero-byte valid JSON | **NO** (only in-progress placeholder) |

`jmhValidateBatchResults` executed → **`BUILD FAILED`** (expected for incomplete batch).

Output: `platform-tracing-bench/build/results/jmh/batches/validated-manifest.json`

```json
{
  "status": "FAIL",
  "expectedClasses": 15,
  "presentClasses": 2,
  "missingClasses": 12,
  "failedClasses": ["space.br1440.platform.tracing.bench.CompositeSamplerBenchmark"],
  "totalBenchmarkResults": 7
}
```

## Batch JSON validation

Per-class validation (2 valid files):

| File | Parses | JMH array | Results | Benchmark prefix match | Duplicates |
|------|--------|-----------|---------|------------------------|------------|
| `AttributePolicyBenchmark.json` | yes | yes | 3 | yes | none |
| `CompositePipelineBenchmark.json` | yes | yes | 4 | yes | none |
| `CompositeSamplerBenchmark.json` | — | — | 0 | — | in-progress (not written) |

No invalid JSON among completed files. No error payloads observed.

## Merge result

**NOT RUN** — `jmhMergeBatchResults` depends on `jmhValidateBatchResults` (FAIL).

Expected outputs when batch completes:

- `platform-tracing-bench/build/results/jmh/batches/merged-results.json`
- `platform-tracing-bench/build/results/jmh/results.json` (from merged batch, not smoke)

## jmhSaveBaseline result

**NOT RUN** — no merged full baseline `results.json`.

`platform-tracing-bench/baselines/` directory does not exist.

When batch is complete, run:

```powershell
./gradlew :platform-tracing-bench:jmhSaveBaselineFromBatch `
  :platform-tracing-bench:jmhSaveBaseline `
  -PjmhBaselineMode=FULL_BASELINE_BATCHED `
  -PjmhProfileId=windows-i5-13500-pr0
```

## jmhCompareBaseline result

**Not applicable for first saved baseline.**

Attempted run failed as expected:

```
Baseline отсутствует: platform-tracing-bench/baselines/<profileId>/results.json
```

No prior saved baseline exists on this host.

## performanceReleaseGate result

**`FAIL_EXPECTED_PENDING_BUDGETS`** (governance — not baseline capture failure)

```
./gradlew :platform-tracing-bench:performanceReleaseGate
2 tests completed, 1 failed
PerformanceReleaseGateTest > все_hard_бюджеты_закрыты_evidence_или_waiver() FAILED
```

Hard budgets in `performance-budgets.yaml` remain PENDING; this is expected pre-baseline state and was not modified.

## Artifacts produced

| Artifact | Status |
|----------|--------|
| `platform-tracing-bench/build/results/jmh/batches/AttributePolicyBenchmark.json` | valid (3 results) |
| `platform-tracing-bench/build/results/jmh/batches/CompositePipelineBenchmark.json` | valid (4 results) |
| `platform-tracing-bench/build/results/jmh/batches/CompositeSamplerBenchmark.json` | 0 bytes (in progress) |
| `platform-tracing-bench/build/results/jmh/batches/validated-manifest.json` | FAIL manifest |
| `platform-tracing-bench/build/results/jmh/batches/manifest.json` | **missing** |
| `platform-tracing-bench/build/results/jmh/batches/merged-results.json` | **missing** |
| `platform-tracing-bench/build/results/jmh/results.json` | exists — **smoke/diagnostic only** (328497 B, warmup 100ms) |
| `platform-tracing-bench/baselines/<profileId>/results.json` | **missing** |
| `platform-tracing-bench/baselines/<profileId>/baseline-metadata.json` | **missing** |
| `docs/architecture/baselines/pr-0/jmh-batch-baseline.log` | batch transcript |
| `docs/architecture/baselines/pr-0/jmh-batch-baseline.log.stdout` | active JMH output (class 3) |

### Gradle tasks added (finalization infra)

| Task | Purpose |
|------|---------|
| `jmhValidateBatchResults` | Inventory validation → `validated-manifest.json` |
| `jmhMergeBatchResults` | Safe JSON merge → `merged-results.json` + `results.json` |
| `jmhSaveBaselineFromBatch` | Depends on merge; chain with `jmhSaveBaseline -PjmhBaselineMode=FULL_BASELINE_BATCHED` |
| `jmhRunBaselineBatchesResume` | Skip valid batch JSON; rerun missing/invalid only |

Batch PASS criteria updated: valid non-empty JSON required, not just `exitCode==0`.

## Remaining blockers

1. Background `jmhRunBaselineBatches` must finish all 15 classes (~hours on this host).
2. If batch task aborts, resume with `./gradlew :platform-tracing-bench:jmhRunBaselineBatchesResume -PjmhHeap=4g` (do not run in parallel with active batch).
3. After all 15 PASS: `jmhValidateBatchResults` → `jmhSaveBaselineFromBatch` + `jmhSaveBaseline -PjmhBaselineMode=FULL_BASELINE_BATCHED`.
4. `performanceReleaseGate` hard budgets still PENDING (separate governance closure).

## Decision for PR-6/7/8

**PR-6 / PR-7 / PR-8 remain blocked.**

Official JMH baseline has not been captured or saved. Batch evidence is partial (2/15) and in progress.

When batch completes and baseline is saved with `FULL_BASELINE_BATCHED` label, re-run this finalization checklist and update status to `JMH_BATCHED_BASELINE_CAPTURED`.

## Commands run

```powershell
# Step 1 — inspect batch status
Get-ChildItem platform-tracing-bench/build/results/jmh -Recurse
Get-Content platform-tracing-bench/build/results/jmh/batches/validated-manifest.json

# Bounded progress polls (3×, 90s apart) — class 3 active, no new completed JSON
# Poll timestamps: 2026-06-12T17:35 / 17:36 / 17:38 MSK

# Step 3 — validate incomplete batch (expected FAIL)
./gradlew :platform-tracing-bench:jmhValidateBatchResults

# Step 8 — compare (no baseline)
./gradlew :platform-tracing-bench:jmhCompareBaseline

# Step 9 — release gate
./gradlew :platform-tracing-bench:performanceReleaseGate

# Step 10 — guardrails
./gradlew pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify pr0BaselineLock
```

Lock check: `platform-tracing-bench/build/results/jmh/jmh.lock` — **not present**. Background batch process (pid 27012) owns the active nested JMH run.

## Next commands (operator, after batch completes)

```powershell
# Verify completeness
./gradlew :platform-tracing-bench:jmhValidateBatchResults

# Merge + save official batched baseline
./gradlew :platform-tracing-bench:jmhSaveBaselineFromBatch `
  :platform-tracing-bench:jmhSaveBaseline `
  -PjmhBaselineMode=FULL_BASELINE_BATCHED `
  -PjmhProfileId=windows-i5-13500-pr0

# Optional post-save compare (requires fresh jmh run or merged results as current)
./gradlew :platform-tracing-bench:jmhCompareBaseline
```

If batch stopped mid-run:

```powershell
./gradlew :platform-tracing-bench:jmhRunBaselineBatchesResume -PjmhHeap=4g
```
