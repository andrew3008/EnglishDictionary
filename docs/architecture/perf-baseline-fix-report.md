# PERF-BASELINE-FIX — JMH baseline reproducibility

Date: 2026-06-12  
Task: `PERF-BASELINE-FIX`  
Scope: benchmark harness / Gradle only — **no production code changes**

## Status

**`JMH_REPRODUCIBILITY_FIX_READY_BUT_BASELINE_DEFERRED`** (batched full baseline run in progress; finalization 2026-06-12 → `JMH_BASELINE_STILL_BLOCKED` — see [perf-baseline-finalization-report.md](perf-baseline-finalization-report.md))

| Gate | Result |
|------|--------|
| OOM root cause identified | **YES** |
| Reproducibility infra (`jmhHeap`, batch task, logback) | **YES** |
| Diagnostic `warnMissingRequired` | **PASS** (3m37s, `-PjmhHeap=4g`) |
| Quick smoke (all classes) | **PASS** (1m19s) |
| Full single-run baseline | **NOT CAPTURED** (deferred to batch / reference-lab) |
| Batched baseline | **IN PROGRESS** (see [Batch baseline status](#batch-baseline-status)) |

## Root cause analysis

The prior full JMH run did **not** fail from insufficient JMH fork heap alone. Evidence:

1. **Failure location:** Gradle **daemon** `main` thread OOM (`org.gradle.jvmargs=-Xmx2g`), not an explicit JMH fork OOM line.
2. **Log volume:** `docs/architecture/baselines/pr-0/jmh-run.log` grew to **~2.83 GB** during `AttributePolicyBenchmark.warnMissingRequired` warmup (fork 1 of 2).
3. **Log pattern:** After initial WARN-once lines, Logback default **DEBUG** emitted `semconv violation [...] (repeat)` on **every invocation** for each violation (~6 violations × millions of invocations/sec at ~350 ns/op).
4. **Mechanism:** JMH worker stdout was captured by Gradle; massive log buffering exhausted the 2 GiB daemon heap.

Production `AttributePolicy` WARN mode uses log-once + optional DEBUG repeat only when DEBUG is enabled. Benchmark harness had no `logback.xml`, so DEBUG repeat path was active — **not representative of production logging** and unsafe for Gradle-captured runs.

## OOM evidence

| Item | Value |
|------|-------|
| Benchmark class | `space.br1440.platform.tracing.bench.AttributePolicyBenchmark` |
| Benchmark method | `warnMissingRequired` |
| Fork | 1 of 2 (warmup iteration 1) |
| Gradle daemon heap | 2 GiB (`gradle.properties`) |
| JMH fork heap (prior run) | not explicitly set (plugin default) |
| Last successful benchmark | `AttributePolicyBenchmark.disabledValidSet` (both forks) |
| Partial `results.json` | not written (run aborted) |
| Stale lock | `platform-tracing-bench/build/tmp/jmh/jmh.lock` — removed (no owning JMH worker) |

## Changes made

| File | Change |
|------|--------|
| `platform-tracing-bench/src/jmh/resources/logback.xml` | **NEW** — root logger `WARN`; stops DEBUG repeat flood; aligns with production log levels |
| `platform-tracing-bench/build.gradle` | `-PjmhHeap` (default `4g`), `-PjmhExclude`, `-PjmhResultFile`, `-PjmhProfileId`; `jmhRunBaselineBatches` / `jmhRunBaselineBatchesResume`; `jmhValidateBatchResults`, `jmhMergeBatchResults`, `jmhSaveBaselineFromBatch`; batch PASS requires valid non-empty JSON |

No production modules, benchmark class/method names, or `@Param` values changed.

## JMH configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `-PjmhHeap` | `4g` | JMH fork `-Xmx` via `jvmArgs` |
| `-PjmhInclude` | optional | Regex filter (existing) |
| `-PjmhExclude` | optional | Diagnostic exclude only |
| `-PjmhResultFile` | `build/results/jmh/results.json` | Override JSON output path |
| `-PjmhProfileId` | auto (`os-arch-Nc`) | Override hardware profile for `jmhSaveBaseline` |
| `-PjmhQuickSmoke` | — | 1×1×100ms smoke (existing) |
| `-PjmhDev` | — | dev profile (existing) |

### Official full baseline (single run)

```powershell
./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g
./gradlew :platform-tracing-bench:jmhSaveBaseline
```

### Batched baseline (isolation per class)

```powershell
./gradlew :platform-tracing-bench:jmhRunBaselineBatches -PjmhHeap=4g
```

Output: `platform-tracing-bench/build/results/jmh/batches/manifest.json` + one JSON per benchmark class.

**Note:** `jmhMergeBatchResults` concatenates batch JSON into `results.json`. Save with `-PjmhBaselineMode=FULL_BASELINE_BATCHED`. See [perf-baseline-finalization-report.md](perf-baseline-finalization-report.md) for current capture status.

### Diagnostic (failing benchmark)

```powershell
./gradlew :platform-tracing-bench:jmh `
  -PjmhInclude=".*AttributePolicyBenchmark.*warnMissingRequired.*" `
  -PjmhHeap=4g
```

## Diagnostic benchmark results

Command:

```powershell
./gradlew :platform-tracing-bench:jmh `
  -PjmhInclude=".*AttributePolicyBenchmark.*warnMissingRequired.*" `
  -PjmhHeap=4g
```

| Metric | Score |
|--------|-------|
| `warnMissingRequired` avgt | 352.880 ± 5.390 ns/op |
| `gc.alloc.rate.norm` | 840 B/op |
| Wall time | 3m 21s (Gradle 3m 37s) |
| Exit | **BUILD SUCCESSFUL** |

Log: `docs/architecture/baselines/pr-0/jmh-diagnostic-warnMissingRequired.log`  
Artifact: `platform-tracing-bench/build/results/jmh/results.json` (diagnostic subset only)

## Full baseline status

| Item | Status |
|------|--------|
| Single-run `./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g` | **NOT RUN** (ETA ~4h on this host; deferred) |
| `jmhSaveBaseline` | **NOT RUN** (requires full merged `results.json`) |
| `platform-tracing-bench/baselines/<profileId>/` | **NOT CREATED** |

## Batch baseline status

Started after fix verification:

```powershell
./gradlew :platform-tracing-bench:jmhRunBaselineBatches -PjmhHeap=4g
```

| Item | Path |
|------|------|
| Run log | `docs/architecture/baselines/pr-0/jmh-batch-baseline.log` |
| Manifest (when complete) | `platform-tracing-bench/build/results/jmh/batches/manifest.json` |
| Per-class JSON | `platform-tracing-bench/build/results/jmh/batches/<ClassName>.json` |

Benchmark classes in manifest (15): discovered from `src/jmh/java/**/*Benchmark.java`.

**Update this section when batch completes:** if `manifest.overallStatus == PASS` → outcome becomes `JMH_BATCHED_BASELINE_CAPTURED`.

## Remaining blockers

1. Full merged `results.json` + `jmhSaveBaseline` not yet captured on this host.
2. `jmhCompareBaseline` / `PerformanceReleaseGateTest` hard budgets still require official baseline evidence per `performance-budgets.yaml`.
3. Batched baseline merge/save tasks exist but batch run incomplete (2/15 valid at finalization time).

## Commands run

```powershell
Get-Location
./gradlew --version
java -version
Get-ChildItem -Recurse -Filter "jmh.lock" .
Remove-Item platform-tracing-bench/build/tmp/jmh/jmh.lock  # stale

./gradlew :platform-tracing-bench:test
./gradlew :platform-tracing-bench:jmh `
  -PjmhInclude=".*AttributePolicyBenchmark.*warnMissingRequired.*" -PjmhHeap=4g
./gradlew :platform-tracing-bench:jmh -PjmhQuickSmoke
./gradlew pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify pr0BaselineLock
./gradlew :platform-tracing-bench:jmhRunBaselineBatches -PjmhHeap=4g  # background
```

Environment:

| | |
|-|-|
| OS | Windows 10 amd64 |
| Gradle | 8.14 |
| JDK | 21.0.6 |
| Gradle daemon heap | 2 GiB (unchanged) |

## Artifacts produced

| Artifact | Description |
|----------|-------------|
| `platform-tracing-bench/src/jmh/resources/logback.xml` | Harness log config |
| `docs/architecture/baselines/pr-0/jmh-diagnostic-warnMissingRequired.log` | Diagnostic run log |
| `docs/architecture/baselines/pr-0/jmh-quick-smoke-after-fix.log` | Post-fix smoke log |
| `docs/architecture/baselines/pr-0/jmh-batch-baseline.log` | Batched run log (in progress) |
| `platform-tracing-bench/build/results/jmh/results.json` | Latest diagnostic/smoke output (not full baseline) |

## Hard gates before PR-5/6/7/8

| PR | Blocked? | Reason |
|----|----------|--------|
| PR-5 (test duplication only) | **May proceed** if limited to tests, no production moves |
| PR-6 sampling extraction | **BLOCKED** until full JMH baseline captured + saved |
| PR-7 scrubbing extraction | **BLOCKED** |
| PR-8 validation/enrichment extraction | **BLOCKED** |

Official acceptance remains:

```powershell
./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g
./gradlew :platform-tracing-bench:jmhSaveBaseline
```

Or documented `FULL_BASELINE_BATCHED` manifest with all 15 classes PASS and reference-lab sign-off on batch semantics.
