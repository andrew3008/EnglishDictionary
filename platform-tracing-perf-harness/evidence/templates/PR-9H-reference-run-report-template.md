# PR-9H reference run report (REFERENCE tier)

> Fill after official Kubernetes reference execution (PR-9H-E2+). Do not fill from SMOKE artifacts.
> PR-9H-E2 first run: W-004 remains **OPEN**; this report is a candidate only.

## Executive summary

- Profile id:
- Run id:
- Git commit:
- Evidence validity: authoritative / non-authoritative
- Decision: W-004 remains OPEN / candidate for resolution (PR-9H-G)

## Evidence validity summary

| Check | Value | Pass |
|-------|-------|------|
| `w004Eligible` | | |
| `nonAuthoritative` | | |
| `backpressureEvidenceValid` | | |
| `achievedRpsPct` | | |
| `droppedIterations` | | |
| `throttleRatioPct` | | |

### nonAuthoritativeReasons

(list from summary, e.g. `singleRunOnly`, `provisionalBudgetOnly`)

### missingMetrics

(list from summary `caveats.missingMetrics`)

## Environment / profile

| Field | Value |
|-------|-------|
| Cluster alias | |
| Namespace | |
| Node pool / k8sNodeProfile | |
| Kernel / cgroup | |
| App image | |
| App CPU req/limit | |
| App memory req/limit | |
| Collector image/version | |
| OTel agent / extension version | |
| JDK / Spring Boot | |
| JFR settings / jfrStartMode | |

## Scenario matrix

(link to `config/profiles/reference-scenario-matrix.md` — which scenarios executed)

## Commands

```text
(paste kubectl / k6 / prometheus query commands)
```

## Resource limits

(document enforced limits vs placeholders)

## Load model

- Executor: `constant-arrival-rate` (open model)
- TARGET_RPS (pinned, same for S0/S1/S4):
- Achieved RPS avg:
- achievedRpsPct:
- dropped_iterations:
- Warmup:
- Steady window (>= 10m):
- steadyWindowStart / steadyWindowEnd:

## CPU results

| Scenario | coresRateAvg | per1kRps | vs S0 Δ% | throttleRatioPct | Budget | Pass/Fail |
|----------|--------------|----------|----------|------------------|--------|-----------|

PromQL used: `rate(container_cpu_usage_seconds_total[steady_window])`

## RSS / working-set results

| Scenario | workingSetBytesAvg | metricSource | vs S0 Δ% | Soak trend | Budget | Pass/Fail |
|----------|-------------------|--------------|----------|------------|--------|-----------|

Source: `container_memory_working_set_bytes/cAdvisor`

## Latency results

| Scenario | p50 | p95 | p99 | source | vs S0 | Budget | Pass/Fail |
|----------|-----|-----|-----|--------|-------|--------|-----------|

Source: `k6/http_req_duration/constant-arrival-rate` (not JMH)

## Error rate

| Scenario | Rate | Threshold | budgetStatus | Pass/Fail |
|----------|------|-----------|--------------|-----------|

## JFR / GC results

- jfrStartMode: startup (symmetric S0/test)
- storageRef + sha256:
- totalPauseMs / maxSinglePauseMs / gcType:

## Collector backpressure validity

| Scenario | backpressureEvidenceValid | queueSizeMax | queueCapacity | sendFailed | dropped | receiverRefused |
|----------|---------------------------|--------------|---------------|------------|---------|-----------------|

Exporter type: (must not be debug/logging-only for authoritative evidence)

## Budget table

| Budget | threshold | observed | budgetStatus | Pass |
|--------|-----------|----------|--------------|------|
| cpuOverheadPct | | | provisional/sre-approved | |
| rssOverheadPct | | | | |
| p99LatencyDeltaPct | | | | |
| errorRatePct | | | | |

## Reproduction / variance

- reproductionRunCount:
- variancePct.cpu / rss / p99Latency:
- Two-run policy satisfied: yes / no (required for W-004 RESOLVED)

## Soak status

(PR-9H-F — pending / complete / N/A; required before W-004 RESOLVED)

## W-004 checklist

(link `evidence/templates/W-004-resolution-checklist.md` — itemized pass/fail)

## W-011 / W-012 classification candidates

| Warning | Macro finding | Proposed classification |
|---------|---------------|-------------------------|
| W-011 | S4 @ samplingRatio — CPU/p99/queue | none / diagnostic / high / resolved-candidate |
| W-012 | S1/S3 — p99/GC/allocation | none / diagnostic / high / resolved-candidate |

**E2 first run:** W-011/W-012 remain unchanged unless reproducible macro issue found across two runs.

## Missing evidence

## Caveats

- JMH p99 is not production p99
- SMOKE tier cannot substitute for this report
- Single E2 run cannot resolve W-004

## Decision

- W-004: remains OPEN / candidate (PR-9H-G)
- W-011: unchanged / diagnostic update
- W-012: unchanged / diagnostic update

## Warning Register close-out

- W-004:
- W-011:
- W-012:
