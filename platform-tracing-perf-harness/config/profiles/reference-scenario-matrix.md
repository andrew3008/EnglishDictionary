# REFERENCE scenario matrix (S0–S6) — PR-9H-E1 hardened

Official W-004 evidence uses **open-model** k6 (`scenarios/reference-steady.js`) in Kubernetes.

## Target RPS (pin before PR-9H-E2)

| Parameter | Value |
|-----------|-------|
| `TARGET_RPS` | **REPLACE_TARGET_RPS** (approve before E2; suggest 60–70% of S0 saturation after short calibration) |
| RPS band | Same value for **S0, S1, S4** — no RPS sweep in E2 first run |
| Warmup | 2m (minimum) |
| Steady | 10m (minimum; `steadyWindowSec` >= 600) |

## Scenario matrix

| ID | Spring profile | Tracing | Validation | Sampling | Scrubbing | Endpoint mix | Load | Gate | W-004 | W-011/12 |
|----|----------------|---------|------------|----------|-----------|--------------|------|------|-------|----------|
| S0 | `s0-baseline` | off | — | — | — | `/perf/fast`, `/perf/work` | open-model @ TARGET_RPS | **E2 required** | Control baseline | — |
| S1 | `s1-tracing` | on | disabled | default | default | `/perf/fast`, `/perf/work` | open-model @ TARGET_RPS | **E2 required** | Trace/export cost | W-012 sampling macro |
| S2 | `s2-validation-lenient-valid` | on | lenient valid | default | default | + `/perf/validation/valid` | open-model @ TARGET_RPS | Must-have (W-004) | Validation cost | — |
| S3 | `s3-validation-lenient-missing` | on | lenient missing | default | default | + `/perf/validation/missing` | open-model @ TARGET_RPS | Must-have (W-004) | Warn path | W-012 missing-attr macro |
| S4 | `s4-production` | on | lenient | **0.1** (document) | on | all `/perf/*` | open-model @ TARGET_RPS | **E2 required** | Production composite | W-011 drop path @ samplingRatio |
| S5 | `s5-strict-diagnostic` | on | strict | default | default | validation only | open-model | **Defer E2** | Exception path | — |
| S6 | `s6-soak` | on | lenient | 0.1 | on | S4 mix | long open-model | **PR-9H-F** | RSS trend | — |

### S4 production mode detail

- `samplingRatio`: 0.1 (10%) — exercises drop path for W-011 macro signal
- `validationMode`: lenient
- `scrubbingEnabled`: true

### W-011 macro signal (S4)

At documented `samplingRatio`, record macro CPU (`cpu.per1kRps`), p99, collector queue depth/drops.
Compare vs S1 to isolate sampling/drop-path overhead beyond base tracing.

### W-012 macro signal (S1 + S3)

- **S1**: sampling-on baseline without validation
- **S3**: missing-attribute warn path — record p99, GC, allocation impact vs S2

## PR-9H-E2 first run scope

Execute (minimum):

1. **S0** (×2 if lab policy requires noise calibration)
2. **S1**
3. **S4**

Strongly recommended if time permits: S2, S3.

Deferred: S5 (diagnostic), S6 (soak PR-9H-F).

**E2 cannot resolve W-004.** Produces first credible REFERENCE candidate only.

## Metrics required (REFERENCE summary)

All scenarios: `cpu.per1kRps`, working-set, p50/p95/p99, error rate, load validity fields.

S1+: exporter/agent export metrics where available.

S4/S6: collector queue metrics (`backpressureEvidenceValid=true` required).

All with JFR: GC pause summary; allocation optional.

## Execution order

1. Calibrate TARGET_RPS (~60–70% S0 saturation)
2. S0 (control) — document as denominator for all deltas
3. S1, S4 (E2 minimum); S2, S3 if time
4. S5 isolated diagnostic (later)
5. S6 soak (PR-9H-F) after S4 baseline

See `config/k8s/k6-job.yaml` — substitute `REPLACE_*` per scenario run.
