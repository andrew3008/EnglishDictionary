# PR-9H-E2L-JFR-finalize — Duration-bound JFR finalization report

**Status:** `COMPLETED_PARSEABLE_JFR_AND_RERUN_NON_AUTHORITATIVE`  
**runId:** `20260614-local-jfr-finalize-1`  
**Date:** 2026-06-15

## Classification

| Field | Value |
|-------|-------|
| evidenceTier | REFERENCE |
| labTier | LOCAL_REFERENCE_LAB |
| w004Eligible | **false** |
| nonAuthoritative | **true** |
| nonAuthoritativeReasons | localEnvironment, singleRunOnly, provisionalBudgetOnly |

## 1. What changed

- Duration-bound JFR: `StartFlightRecording=...,disk=true,duration=7m,filename=/tmp/jfr/local-reference.jfr`
- `lib/jfr-local.sh`: `lab_jfr_wait_for_finalized`, `lab_jfr_verify_parseable_*`, `lab_jfr_copy_finalized_from_pod`
- Updated smoke/scenario collection scripts; fixed JFR event parser (nested JSON + ISO durations)
- New run `20260614-local-jfr-finalize-1` with parseable JFR + GC summaries

## 2. What did not change

- Production/runtime code, W-004 (**OPEN**), W-011/W-012, official `evidence/reference/`
- Prior runs (`20260614-local-jfr-fix-1`, etc.) unchanged

## 3. Local context verification

- Host: Gentoo T480, `kind-platform-tracing-reference-lab`, namespace `platform-tracing-reference-lab`
- kubectl context verified before run

## 4. Previous locked stream root cause

Continuous recording copied via largest repository `.jfr` while stream open → `locked_stream_state`. `filename=` path was 0 bytes; JRE has no `jcmd`.

## 5. Finalization strategy selected

**Strategy A — duration-bound startup recording** (no jcmd). Recording auto-stops after `duration=`; `/tmp/jfr/local-reference.jfr` finalized and parseable.

## 6. Duration-bound JFR configuration

```
-XX:FlightRecorderOptions=repository=/tmp/jfr/repository
-XX:StartFlightRecording=name=local-reference,settings=profile,disk=true,duration=7m,filename=/tmp/jfr/local-reference.jfr
```

Smoke: `duration=2m`. Scenarios: `duration=7m` (covers 1m warmup + 3m steady + buffer).

## 7. JFR smoke proof

| Field | Value |
|-------|-------|
| fileSizeBytes | 1,255,264 |
| sha256 | `1bed0755ec0f4727fe394f34b04425dc501b1bd5f7925038982319705b6437f1` |
| parserStatus | ok |
| durationSeconds | 120 |
| jfr summary | **PASS** |

## 8. Scenarios rerun

S0, S1, S4 @ TARGET_RPS=20 (1m warmup + 3m steady)

## 9. k6 results

| Scenario | achieved RPS | p95 (ms) | errors | dropped |
|----------|--------------|----------|--------|---------|
| S0 | 20.008 | 1.57 | 0% | 0 |
| S1 | 20.000 | 1.98 | 0% | 0 |
| S4 | 20.004 | 1.96 | 0% | 0 |

## 10. Prometheus/cAdvisor (compact)

| Scenario | coresRateAvg | per1kRps | workingSet (approx) |
|----------|--------------|----------|---------------------|
| S0 | 0.030 | 1.48 | ~309 MiB |
| S1 | (see prometheus-compact-summary.json) | | |
| S4 | (see prometheus-compact-summary.json) | | |

## 11. Collector

- queue_size=0, capacity=1000, backpressureEvidenceValid=false

## 12. Finalized JFR artifacts

Raw path: `/var/tmp/platform-tracing-reference-lab-artifacts/20260614-local-jfr-finalize-1/`

| Scenario | fileSizeBytes | sha256 (prefix) | parserStatus |
|----------|---------------|-----------------|--------------|
| jfr-smoke | 1,255,264 | 1bed0755… | ok |
| S0 | 2,085,662 | bfc7c809… | ok |
| S1 | 2,750,615 | 97d3deba… | ok |
| S4 | 2,800,865 | 6327bef2… | ok |

Copy source: **only** finalized `/tmp/jfr/local-reference.jfr` after `jfr summary` succeeds in pod.

## 13. JFR parser results

- parserTool: `jfr/21.0.7`
- All scenarios: `status=collected`, `parserStatus=ok`, `finalizationMode=durationBound`
- No `locked_stream_state` errors

## 14. GC summary extracted (S0/S1/S4)

| Scenario | gcPauseCount | gcPauseTotalMillis | gcPauseMaxMillis | gcPauseAvgMillis |
|----------|--------------|--------------------|------------------|------------------|
| S0 | 22 | 495.3 | 116.6 | 22.5 |
| S1 | 20 | 517.7 | 135.6 | 25.9 |
| S4 | 18 | 621.1 | 159.6 | 34.5 |

GC types observed: G1New, G1Old, GC Pause, Pause Cleanup, Pause Remark.

## 15. Missing JFR details remaining

- Allocation sample summary not aggregated (`ObjectAllocationSample` uses weight semantics; not in compact summary)
- `jfrDetailedGcSummary` **removed** from `missingMetrics` where GC pause data extracted

## 16. Evidence produced

`evidence/reference-local/gentoo-local-reference-lab-v1/20260614-local-jfr-finalize-1/{jfr-smoke,S0,S1,S4}/`

## 17–21. Validation / hygiene / W-004

- No raw `.jfr` committed; Gradle validation required post-merge
- W-004 remains **OPEN**; official SRE/pre-prod E2 still required

## 22. Remaining limitations

- LOCAL_REFERENCE_LAB only; single run; provisional budgets
- kind DNS pod-IP workaround
- JRE image — no jcmd fallback needed with duration-bound recording

## 23. Recommended next PR

- **PR-9H-E2-run** — official SRE/pre-prod Kubernetes REFERENCE when environment available

## Warning Register close-out

- Warnings added: none
- Warnings resolved: none
- Warnings reclassified: none
- Warnings intentionally unchanged: W-003 … W-013
- Warning register updated: yes
