# PR-9H macro/load/RSS reference evidence template

> Fill only from **REFERENCE** tier runs (Kubernetes/pre-prod). SMOKE tier summaries are non-authoritative.

## Profile / environment

| Field | Value |
|-------|-------|
| Evidence tier | REFERENCE / SMOKE |
| Profile ID | |
| Run ID | |
| Cluster / lab | |
| CPU limit | |
| Memory limit | |
| JDK | |
| Agent + extension version | |

## Scenario matrix

| Scenario | Config profile | Purpose |
|----------|----------------|---------|
| S0 | s0-baseline | Control |
| S1 | s1-tracing | Trace/export without validation |
| S2 | s2-validation-lenient-valid | Lenient valid spans |
| S3 | s3-validation-lenient-missing | Lenient missing attrs |
| S4 | s4-production | Combined production mode |
| S5 | s5-strict-diagnostic | Diagnostic only |
| S6 | s6-soak | Soak / RSS trend |

## Commands

```text
(paste exact commands)
```

## App config

(link or summary of active Spring profiles / agent env)

## Load model

- Generator: k6 open-model (`constant-arrival-rate`)
- Target RPS:
- Warmup:
- Steady duration:

## CPU summary

| Scenario | Δ vs S0 | Budget | Pass/Fail |
|----------|---------|--------|-----------|

## RSS / working-set summary

| Scenario | Container working-set | Δ vs S0 | Soak trend | Pass/Fail |
|----------|----------------------|---------|------------|-----------|

## p95 / p99 latency summary

| Scenario | p95 | p99 | Δ vs S0 | Pass/Fail |
|----------|-----|-----|---------|-----------|

## Error rate

| Scenario | Rate | Threshold | Pass/Fail |
|----------|------|-----------|-----------|

## GC / JFR summary

(control-matched JFR; note overhead differencing)

## Export queue / dropped spans

| Scenario | Queue depth | Drops | Backpressure observed |
|----------|-------------|-------|----------------------|

## Soak trend

(RSS/GC/latency over window — S6 only)

## W-004 resolution checklist

- [ ] CPU overhead evidence (REFERENCE, open-model)
- [ ] RSS/working-set under limits
- [ ] p95/p99 vs control
- [ ] JFR/GC characterized
- [ ] Error rate within threshold
- [ ] Exporter queue/backpressure (sink not masking)
- [ ] Soak stability
- [ ] Reproducibility (two runs)
- [ ] Evidence report committed (summary only)
- [ ] Warning Register updated

## W-011 / W-012 classification notes

| Warning | Macro finding | Classification |
|---------|---------------|----------------|
| W-011 dropPath JMH | | DIAGNOSTIC / HIGH / RESOLVED |
| W-012 sample-mode JMH | | DIAGNOSTIC / HIGH / RESOLVED |

## What this evidence does not prove

- Production fleet-wide SLOs
- All deployment topologies
- JMX deployment security (W-007)

## Warning Register close-out

- W-004:
- W-011:
- W-012:
