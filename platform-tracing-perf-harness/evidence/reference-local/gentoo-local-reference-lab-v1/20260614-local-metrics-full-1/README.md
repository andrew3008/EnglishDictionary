# LOCAL_REFERENCE_LAB evidence — PR-9H-E2L-FULL

**runId:** `20260614-local-metrics-full-1`  
**Status:** `COMPLETED_PARTIAL_LOCAL_METRICS_RERUN_NON_AUTHORITATIVE`  
**labTier:** `LOCAL_REFERENCE_LAB` | **w004Eligible:** `false`

## Completed

| Item | S0 | S1 | S4 |
|------|----|----|-----|
| k6 (RPS=20, 1m+3m) | ✓ | ✓ | ✓ |
| prometheus-compact-summary | ✓ | ✓ | ✓ |
| collector-compact-summary | ✓ | ✓ | ✓ |
| reference-summary.json | ✓ | ✓ | ✓ |
| JFR/GC summary | ✗ empty dump | ✗ | ✗ |

## k6 results

| Scenario | achieved RPS | p95 (ms) | errors | dropped |
|----------|--------------|----------|--------|---------|
| S0 | 20.008 | 1.52 | 0% | 0 |
| S1 | 20.004 | 1.97 | 0% | 0 |
| S4 | 20.004 | 1.99 | 0% | 0 |

## Prometheus (cAdvisor) — S0 example

- cpu.coresRateAvg: 0.027
- cpu.per1kRps: 1.37
- cpu.throttleRatioPct: 0.0
- memory.workingSetBytesAvg: ~323 MB (S0)

Not official SRE/pre-prod evidence. W-004 remains **OPEN**.

See `reference-report.md` for full report.
