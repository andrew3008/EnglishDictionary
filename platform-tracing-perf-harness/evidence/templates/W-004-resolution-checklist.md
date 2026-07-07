# W-004 resolution checklist — REFERENCE + soak evidence (PR-9H-E1 hardened)

W-004 may be marked **RESOLVED** only when **all** items below are satisfied with
**REFERENCE** tier evidence (Kubernetes/pre-prod-like) plus **soak** (PR-9H-F).

Local **SMOKE** artifacts (`evidenceTier: SMOKE`, `w004Eligible: false`) **cannot** satisfy this checklist.
A single PR-9H-E2 run **cannot** resolve W-004.

## Environment

- [ ] Kubernetes or approved pre-prod-like cluster (not local dev workstation)
- [ ] Same cluster/namespace for S0, S1, S4 comparison runs
- [ ] Enforced CPU and memory **requests/limits** documented in summary
- [ ] cgroup version and kernel version documented (`environment.cgroupVersion`, `environment.kernelVersion`)
- [ ] OTel agent, extension, Collector versions documented
- [ ] `gitCommit` recorded (not `unknown`)
- [ ] Profile id committed (`evidence/reference/<profileId>/`)

## Load (k6 constant-arrival-rate)

- [ ] `metrics.load.executor` = `constant-arrival-rate`
- [ ] Same `TARGET_RPS` for S0, S1, S4 (pin before E2; see scenario matrix)
- [ ] `metrics.load.achievedRpsPct` >= 0.95
- [ ] `metrics.load.droppedIterations` = 0
- [ ] Steady window >= 10 minutes (`metrics.load.steadyWindowSec` >= 600)
- [ ] CFS throttle ratio < 5% (`metrics.cpu.throttleRatioPct` <= 5)

## Scenario matrix (REFERENCE runs)

- [ ] **S0** — baseline control (tracing disabled / agent absent) — **mandatory denominator**
- [ ] **S1** — tracing on, validation disabled
- [ ] **S2** — validation lenient, valid spans
- [ ] **S3** — validation lenient, missing attrs
- [ ] **S4** — combined production mode (sampling + scrubbing + validation)
- [ ] **S5** — strict diagnostic (isolated; **not** production hot-path gate)
- [ ] **S6** — soak profile (long window) — **PR-9H-F**, required before W-004 RESOLVED

## CPU

- [ ] Primary raw: `rate(container_cpu_usage_seconds_total[steady_window])` → `metrics.cpu.coresRateAvg`
- [ ] Primary comparison: `metrics.cpu.per1kRps` = `coresRateAvg / achievedRpsAvg * 1000`
- [ ] CPU overhead vs S0 within **SRE-approved** budget (`budgets.cpuOverheadPct.budgetStatus` = `sre-approved` or `architecture-approved`)
- [ ] Throttle ratio: `rate(container_cpu_cfs_throttled_periods_total) / rate(container_cpu_cfs_periods_total)` → `throttleRatioPct`

## Memory (working set)

- [ ] Primary: `container_memory_working_set_bytes` via cAdvisor/kubelet
- [ ] `metrics.memory.metricSource` = `container_memory_working_set_bytes/cAdvisor`
- [ ] Working set delta vs S0 within **SRE-approved** budget
- [ ] Not JVM heap alone; distinguish from page cache

## Latency

- [ ] Primary source: `k6/http_req_duration` under constant-arrival-rate
- [ ] `metrics.latency.source` = `k6/http_req_duration/constant-arrival-rate`
- [ ] p95/p99 from steady window with `steadyWindowStart` / `steadyWindowEnd` recorded
- [ ] p99 delta vs S0 within **SRE-approved** budget
- [ ] Do not use JMH p99 as macro p99

## JFR / GC

- [ ] `environment.jfrStartMode` = `startup` (`-XX:StartFlightRecording` on Java 21)
- [ ] JFR settings symmetric across S0 and test scenarios
- [ ] `artifacts.jfr.storageRef` + `sha256` (raw JFR stored externally, not committed)
- [ ] `metrics.gc.totalPauseMs`, `maxSinglePauseMs`, `gcType` recorded

## Collector / backpressure

- [ ] Non-debug exporter (otlphttp/reference_sink or equivalent)
- [ ] `metrics.collector.backpressureEvidenceValid` = true
- [ ] Queue metrics collected:
  - `otelcol_exporter_queue_size`
  - `otelcol_exporter_queue_capacity`
  - `otelcol_exporter_send_failed_spans`
  - `otelcol_exporter_enqueue_failed_spans`
  - `otelcol_processor_dropped_spans`
  - `otelcol_receiver_refused_spans`
- [ ] Debug/logging-only exporter **invalidates** backpressure evidence

## Reproducibility and budgets

- [ ] At least **two** independent REFERENCE runs (`reproducibility.reproductionRunCount` >= 2)
- [ ] Variance within agreed tolerance (`reproducibility.variancePct`)
- [ ] All budgets have `budgetStatus` = `sre-approved` or `architecture-approved` (not `provisional`)
- [ ] Reference summary validates against `reference-summary.schema.json` with `w004Eligible: true`

## Soak (PR-9H-F)

- [ ] Multi-hour window under S4-equivalent settings
- [ ] Working-set trend flat (no leak)
- [ ] GC/latency stable over soak window

## Artifacts and governance

- [ ] Compact report committed (`PR-9H-reference-run-report-template.md` filled)
- [ ] Raw JFR/k6/Prometheus dumps **not** committed (paths documented only)
- [ ] Warning Register updated with evidence links
- [ ] Architecture/SRE sign-off on evidence contract and budgets

## Warning classification (PR-9H-G)

- [ ] **W-011** — reclassify only with reproducible REFERENCE macro evidence (not JMH alone)
- [ ] **W-012** — reclassify only with reproducible REFERENCE macro evidence (not JMH alone)
- [ ] Single E2 run cannot reclassify W-011/W-012

## Provisional budget guidance (not final until SRE-approved)

| Metric | Provisional threshold |
|--------|----------------------|
| CPU overhead delta S4 vs S0 (per1kRps) | < 3% |
| RSS/working-set delta | < 10% |
| p99 latency delta | < 15% |
| Error rate | < 0.1% |
| CFS throttle ratio | < 5% |
| achievedRpsPct | >= 0.95 |
| dropped_iterations | 0 |

## Explicit non-qualifiers

- JMH microbenchmarks alone (W-003 domain)
- Local SMOKE tier (`build/perf-results/smoke/`)
- Windows workstation without enforced cgroup limits
- Single non-repeated run (`singleRunOnly`)
- Provisional budgets only (`provisionalBudgetOnly`)
- Missing S0 control baseline
- Debug/logging collector exporter as sole sink
