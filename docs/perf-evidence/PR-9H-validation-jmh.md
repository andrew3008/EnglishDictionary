# PR-9H — Validation JMH evidence

## PR-9H-A (foundation)

See sections below for benchmark matrix. Added `ValidatingSpanProcessorBenchmark`.

## PR-9H-B (gate + artifact hygiene)

### Artifact hygiene (PR-9H-A regression fix)

| Path | Role |
|------|------|
| `build/results/jmh/results.json` | Default output for official `jmh` / `jmhCompareBaseline` |
| `build/results/jmh-smoke/results.json` | **Isolated** output when `-PjmhQuickSmoke` — must not poison tests |
| `build/results/jmh/batches/*.json` | Per-class batch runs (`jmhRunBaselineBatches`) |
| `baselines/<profileId>/results.json` | Committed hardware-profile baseline (mutable only via merge tasks) |
| `src/test/resources/jmh-baseline-fixtures/*.json` | **Immutable** comparator test fixtures |

Comparator tests must **not** read mutable `build/results/jmh/results.json` as fixture input.

Quick-smoke output is **not** a release baseline.

### Gate integration

- `JmhBenchmarkGatePolicy` — classifies validation hard-gate vs diagnostic benchmarks.
- `JmhBaselineComparator` — diagnostic validation benchmark (`validationStrictAllowedMissingAttrDiagnostic`) uses diagnostic gate for **avgt**; alloc hard gate unchanged for non-diagnostic benchmarks.
- `jmhCompareBaseline` — unchanged task; compares current `results.json` vs `baselines/<profileId>/results.json`.
- `jmhMergeValidationIntoBaseline` — merges `batches/ValidatingSpanProcessorBenchmark.json` into profile baseline (run with `-PjmhExclude=.*MissingAttrDiagnostic`).

### Hard-gate validation benchmarks

| Method | Gate |
|--------|------|
| `validationDisabled` | Hard |
| `validationLenientValidSpan` | Hard |
| `validationLenientMissingRequiredAttr` | Hard |
| `validationStrictAllowedValidSpan` | Hard |
| `holderCurrentRead` | Hard |
| `policySnapshotRead` | Hard |
| `validationStrictAllowedMissingAttrDiagnostic` | **Diagnostic only** |

Budget policy (same as suite-wide JMH): avgt FAIL +25% / WARN +10%; alloc FAIL +15% / WARN +5%.
See `performance-budgets.yaml` entries `jmh-validation-latency-regression`, `jmh-validation-alloc-regression`.

### Baseline capture

Use `-PjmhProfileId=windows-i5-13500-pr0` when profile auto-detect differs from committed baseline directory.

```text
./gradlew :platform-tracing-bench:jmh \
  -PjmhInclude=ValidatingSpanProcessorBenchmark \
  -PjmhExclude=.*MissingAttrDiagnostic \
  -PjmhResultFile=platform-tracing-bench/build/results/jmh/batches/ValidatingSpanProcessorBenchmark.json

./gradlew :platform-tracing-bench:jmhMergeValidationIntoBaseline -PjmhProfileId=windows-i5-13500-pr0

./gradlew :platform-tracing-bench:jmhCompareBaseline -PjmhProfileId=windows-i5-13500-pr0
```

PR-9H-B merged **provisional jmhDev** validation avgt entries into `baselines/windows-i5-13500-pr0/results.json`. Full 2×5×5 reference capture remains PR-9H-B2.

## PR-9H-B2 (full reference baseline + allocation evidence)

### Profile and hardware

| Field | Value |
|-------|-------|
| Profile ID | `windows-i5-13500-pr0` |
| Hardware | Intel Core i5-13500 (20 logical cores per `baseline-metadata.json`) |
| OS | Windows 10 amd64 |
| JDK | 21.0.6 (HotSpot), `-Xmx4g` |
| JMH protocol | 2 forks × 5 warmup × 5 measurement × 10 s; GC profiler enabled |
| Baseline mode | `FULL_REFERENCE_2x5x5` (recorded in `baseline-metadata.json`) |

**Profile policy:** `windows-i5-13500-pr0` is the sole committed hardware-profile baseline directory for composite/scrubbing/validation JMH. Auto-detect yields `windows-10-amd64-20c` on this host — **always pass `-PjmhProfileId=windows-i5-13500-pr0`** for merge/compare against committed baselines.

### Commands run (2026-06-14)

```text
./gradlew :platform-tracing-bench:jmh \
  -PjmhInclude=ValidatingSpanProcessorBenchmark \
  -PjmhExclude=.*MissingAttrDiagnostic \
  -PjmhResultFile=platform-tracing-bench/build/results/jmh/batches/ValidatingSpanProcessorBenchmark.json

./gradlew :platform-tracing-bench:jmhMergeValidationIntoBaseline \
  -PjmhProfileId=windows-i5-13500-pr0 \
  -PjmhBaselineMode=FULL_REFERENCE_2x5x5

./gradlew :platform-tracing-bench:jmhCompareBaseline \
  -PjmhProfileId=windows-i5-13500-pr0
```

Artifacts:

- Batch run: `platform-tracing-bench/build/results/jmh/batches/ValidatingSpanProcessorBenchmark.json`
- Committed baseline: `platform-tracing-bench/baselines/windows-i5-13500-pr0/results.json` (6 validation avgt entries with `secondaryMetrics.·gc.alloc.rate.norm`)
- Compare input: `platform-tracing-bench/build/results/jmh/results.json` (copied from batch by merge task)

### Hard-gate benchmarks (6)

| Method | avgt (ns/op) | gc.alloc.rate.norm (B/op) |
|--------|-------------:|--------------------------:|
| `holderCurrentRead` | 1.028 | ~0 |
| `policySnapshotRead` | 0.622 | ~0 |
| `validationDisabled` | 125.478 | 392 |
| `validationLenientValidSpan` | 311.155 | 664 |
| `validationLenientMissingRequiredAttr` | 341.034 | 856 |
| `validationStrictAllowedValidSpan` | 243.070 | 664 |

### Diagnostic benchmark (excluded from hard avgt gate)

| Method | Role |
|--------|------|
| `validationStrictAllowedMissingAttrDiagnostic` | Strict exception path — diagnostic only; excluded via `-PjmhExclude=.*MissingAttrDiagnostic` for baseline capture and hard avgt gate |

### Comparator result

```
matched=6, NEW=0, hardWARN=0, hardFAIL=0, diagWARN=0, diagFAIL=0
All 6 hard-gate validation benchmarks: latency +0.0%, alloc +0.0%
```

Diagnostic benchmark not in baseline or hard avgt gate (by design).

### W-003 status after PR-9H-B2

**RESOLVED** — full reference-profile validation JMH baseline captured with latency and allocation evidence; comparator clean.

### What this still does not prove

- Production CPU/RSS/p99 (W-004 remains **OPEN**; macro/soak/RSS deferred to PR-9H-C)
- Pre-prod sign-off from JMH alone
- SampleTime / p99 as production SLO (W-012 remains **DIAGNOSTIC / OPEN**)
- `dropPath` sampling instability (W-011 remains **DIAGNOSTIC / OPEN**)

Quick smoke (fixture check only):

```text
./gradlew :platform-tracing-bench:jmh -PjmhQuickSmoke -PjmhInclude=ValidatingSpanProcessorBenchmark
```

### What this does not prove

- Production CPU/RSS/p99 (W-004 OPEN)
- Pre-prod sign-off from JMH alone
- SampleTime / p99 as production SLO (W-012)

## Benchmark matrix (PR-9H-A)

| Method | Role | Gate class |
|--------|------|------------|
| `validationDisabled` | `enabled=false` passthrough | Hard |
| `validationLenientValidSpan` | Production lenient, attrs present | Hard |
| `validationLenientMissingRequiredAttr` | Lenient missing attrs + throttled warn | Hard |
| `validationStrictAllowedValidSpan` | Strict valid (diagnostic profile) | Hard |
| `holderCurrentRead` | Lock-free `holder.current()` | Hard |
| `policySnapshotRead` | Snapshot field access | Hard |
| `validationStrictAllowedMissingAttrDiagnostic` | Strict exception path | Diagnostic |

## Anti-patterns

1. Do not claim production readiness from JMH only.
2. Do not refresh baseline to hide a regression.
3. Do not optimize from a single noisy microbenchmark run.
4. Do not treat strict exception-path benchmarks as production hot-path gates.
5. Do not treat JMH SampleTime p99 as production p99.

## Related warnings

| ID | Status after PR-9H-B2 |
|----|----------------------|
| W-003 | RESOLVED |
| W-004 | OPEN |
| W-011 | OPEN / DIAGNOSTIC |
| W-012 | OPEN / DIAGNOSTIC |
