# PR-9H-E2L-JFR-fix / PR-9H-E2L-JFR-summary — Local JFR report

**Status:** `COMPLETED_PARTIAL_JFR_ARTIFACT_PARSE_NON_AUTHORITATIVE`  
**runId:** `20260614-local-jfr-fix-1`  
**Date:** 2026-06-15

## Classification

| Field | Value |
|-------|-------|
| evidenceTier | REFERENCE |
| labTier | LOCAL_REFERENCE_LAB |
| w004Eligible | **false** |
| nonAuthoritative | **true** |
| nonAuthoritativeReasons | localEnvironment, singleRunOnly, provisionalBudgetOnly, missingMetrics |

## 1. What changed

- JFR JVM opts: `disk=true`, `FlightRecorderOptions=repository=/tmp/jfr/repository`, removed ineffective `dumponexit` for long-running pods
- Collection: copy largest `*.jfr` from pod (JRE has no `jcmd`)
- Scripts: `lib/jfr-local.sh`, `run-local-jfr-smoke.sh`, `jfr-smoke-check.sh`, updated `collect-local-jfr-summary.sh`, `deploy-perf-app-lab.sh`
- Evidence: S0/S1/S4 + jfr-smoke under `20260614-local-jfr-fix-1`

## 2. What did not change

- Production/runtime code, W-004 (**OPEN**), W-011/W-012, official `evidence/reference/`

## 3. Local context

- Gentoo T480, `kind-platform-tracing-reference-lab`, namespace `platform-tracing-reference-lab`

## 4. Root cause of previous 0-byte JFR

| Category | Finding |
|----------|---------|
| Primary | **DUMP_ON_EXIT_INEFFECTIVE** + **EMPTY_FILE_FROM_BAD_JCMD_DUMP** |
| Secondary | Without `disk=true`, recording wrote to `/tmp/2026_*` repo (~10 MB), not `filename=` path |
| Secondary | **JFR_NOT_AVAILABLE_IN_JVM** for `jcmd` — Temurin JRE image has no jcmd |
| Not the issue | StartFlightRecording **was applied** (visible in `ps` / `PERF_SCENARIO_JVM_OPTS`) |

## 5. JFR startup configuration

```
-XX:FlightRecorderOptions=repository=/tmp/jfr/repository
-XX:StartFlightRecording=name=local-reference,settings=profile,disk=true,maxage=10m,maxsize=128m,filename=/tmp/jfr/local-reference.jfr
```

## 6. JFR smoke proof

- **PASS** — 1,176,619 bytes copied to `jfr-smoke/local-reference.jfr` (external storage)

## 7. Scenarios rerun

S0, S1, S4 at TARGET_RPS=20 (1m warmup + 3m steady)

## 8. k6 results

| Scenario | achieved RPS | p95 (ms) | errors | dropped |
|----------|--------------|----------|--------|---------|
| S0 | 20.000 | 1.53 | 0% | 0 |
| S1 | 20.008 | 1.96 | 0% | 0 |
| S4 | 20.004 | 1.98 | 0% | 0 |

## 9. Prometheus/cAdvisor (compact)

| Scenario | coresRateAvg | per1kRps | workingSet (approx) |
|----------|--------------|----------|---------------------|
| S0 | 0.027 | 1.37 | ~308 MiB |
| S1 | 0.048 | 2.38 | ~399 MiB |
| S4 | (see prometheus-compact-summary.json) | | |

## 10. Collector

- queue_size=0, capacity=1000, backpressureEvidenceValid=false

## 11. JFR artifacts

| Scenario | fileSizeBytes | sha256 (prefix) |
|----------|---------------|-----------------|
| jfr-smoke | 1,176,619 | (see jfr-summary.json) |
| S0 | 1,975,260 | 20ecd89c… |
| S1 | 2,634,602 | (see jfr-summary.json) |
| S4 | 2,673,111 | (see jfr-summary.json) |

Raw `.jfr` on Gentoo: `/var/tmp/platform-tracing-reference-lab-artifacts/20260614-local-jfr-fix-1/`

## 12. Missing metrics

- `jfrDetailedGcSummary` — JFR parser attempted; raw artifacts in **locked stream state** (see §21)

## 21. JFR parsed summary (PR-9H-E2L-JFR-summary)

**Raw artifact directory:** `/var/tmp/platform-tracing-reference-lab-artifacts/20260614-local-jfr-fix-1/`  
**Parser script:** `scripts/local-reference-lab/summarize-local-jfr.sh`  
**Parser tool (Gentoo host):** `jfr/21.0.7` (Temurin JDK 21.0.7, `/usr/bin/jfr`)

### Scenario-to-artifact mapping

| Scenario | File | fileSizeBytes | sha256 |
|----------|------|---------------|--------|
| jfr-smoke | `jfr-smoke/local-reference.jfr` | 1,176,619 | `d239811783a48b65e0e7a4ffbfb632ddfb8a0b9b862cc5448a3350172a1806e9` |
| S0 | `S0/local-reference.jfr` | 1,975,260 | `20ecd89cdbe0ede3b87c87dcd760bd0b1a5ee344083c94d3eb85de26654b45c1` |
| S1 | `S1/local-reference.jfr` | 2,634,602 | `6ad5f5612816add73e68d16b4f9d7ff7cec064489cf8ca02db5d1bd23d461011` |
| S4 | `S4/local-reference.jfr` | 2,673,111 | `b21836dc633fe004940fd962607cfa7ddc496d554407b6dbb015fe40bb7967d7` |

Mapping: one `local-reference.jfr` per scenario subdirectory; no ambiguous entries.

### Parser status (all scenarios)

| Scenario | status | parserStatus | parserError |
|----------|--------|--------------|-------------|
| jfr-smoke | collected_raw_unparsed | failed | locked_stream_state |
| S0 | collected_raw_unparsed | failed | locked_stream_state |
| S1 | collected_raw_unparsed | failed | locked_stream_state |
| S4 | collected_raw_unparsed | failed | locked_stream_state |

### Root cause (parse failure)

Continuous startup recording with `disk=true` was copied from running pods via `kubectl cp` of the largest repository `.jfr` chunk **while the JVM recording stream was still open**. JDK `jfr summary` / `jfr print` reject such files (`Recording file is stuck in locked stream state`). Temurin **JRE** perf-app image has no `jcmd` for `JFR.dump` finalization. Verified on Gentoo host and inside running pod — same error.

### GC / heap / allocation summary

**Not extracted** — parser could not read any events. `missingDetails` in each `jfr-summary.json`: `jfrDetailedGcSummary`, `gcEvents`, `heapSummary`, `allocationSummary`.

### Updated evidence files

- `jfr-smoke/jfr-summary.json`, `S0/jfr-summary.json`, `S1/jfr-summary.json`, `S4/jfr-summary.json` — parser metadata added
- `reference-summary.json` (S0/S1/S4) — **unchanged** (no real GC metrics to merge)
- W-004 remains **OPEN**; `w004Eligible=false`

### Remaining JFR limitations

- Finalize recording before copy (JDK + `jcmd JFR.dump`, or copy closed chunks after pod stop)
- Until then, compact summaries carry sha256/size/parser error only

### Recommended next PR

- **PR-9H-E2L-JFR-finalize** — JDK sidecar or perf-app JDK image + finalize-before-copy collection (local only)
- **PR-9H-E2-run** — official SRE/pre-prod REFERENCE when environment available

## 13. Evidence produced

`evidence/reference-local/gentoo-local-reference-lab-v1/20260614-local-jfr-fix-1/{jfr-smoke,S0,S1,S4}/`

## 14–16. Validation / hygiene / W-004

- No raw `.jfr` committed; Gradle validation required post-merge
- W-004 remains **OPEN**

## 17–19. Tests / guardrails / limitations

- kind DNS pod-IP workaround remains
- `filename=/tmp/jfr/local-reference.jfr` may stay 0 bytes; collection uses repository `*.jfr`

## 20. Recommended next PR

- **PR-9H-E2L-JFR-finalize** — finalize JFR before copy (local lab)
- **PR-9H-E2-run** — official SRE/pre-prod REFERENCE when environment available

## Warning Register close-out

- Warnings added: none
- Warnings resolved: none
- Warnings reclassified: none
- Warnings intentionally unchanged: W-003 … W-013
- Warning register updated: yes

## Comparison vs 20260614-local-metrics-full-1 (local, non-authoritative)

- Same k6/Prom order of magnitude; JFR moved from **missing** to **collected** (metadata + sha256)
