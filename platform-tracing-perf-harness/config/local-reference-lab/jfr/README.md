# Local lab JFR startup capture (PR-9H-E2L-JFR-fix / PR-9H-E2L-JFR-finalize)

**Local lab only.** Temurin 21 **JRE** image — no `jcmd` in container.

## Finalization strategy (PR-9H-E2L-JFR-finalize) — preferred

**Duration-bound startup recording** — no jcmd required.

```
-XX:FlightRecorderOptions=repository=/tmp/jfr/repository
-XX:StartFlightRecording=name=local-reference,settings=profile,disk=true,duration=7m,filename=/tmp/jfr/local-reference.jfr
```

| Parameter | Smoke | S0/S1/S4 |
|-----------|-------|----------|
| duration | 2m | 7m |
| finalizationMode | durationBound | durationBound |

Expected behavior:

1. Recording starts at JVM startup.
2. JVM finalizes after `duration=` elapses.
3. `/tmp/jfr/local-reference.jfr` becomes parseable (`jfr summary` succeeds).
4. Copy **only** finalized `filename=` path — never open repository chunks.

Set via `LAB_JFR_ENABLED=true`, `LAB_JFR_FINALIZE_MODE=durationBound`, `LAB_JFR_DURATION=7m` in `deploy-perf-app-lab.sh`.

## Root cause (20260614-local-jfr-fix-1 — locked stream)

| Issue | Detail |
|-------|--------|
| Continuous recording | Copied largest repository `.jfr` while stream still open |
| Parser error | `locked_stream_state` from `jfr summary` |
| `filename=` path | 0 bytes during continuous recording |
| jcmd | Unavailable in JRE |

**Do not** use locked artifacts from `20260614-local-jfr-fix-1` as GC evidence.

## Workflow

```bash
export LAB_KUBECTL=/usr/local/bin/kubectl-v136
export LAB_KUBECTX=kind-platform-tracing-reference-lab
export LAB_JFR_FINALIZE_MODE=durationBound
export LAB_JFR_DURATION=7m

# 1. Smoke proof (2m duration)
scripts/local-reference-lab/run-local-jfr-smoke.sh --execute

# 2. Collect after scenario (waits for duration + verifies parseable)
scripts/local-reference-lab/collect-local-jfr-summary.sh 20260614-local-jfr-finalize-1 S0

# 3. Parse compact summary
scripts/local-reference-lab/summarize-local-jfr.sh \
  --raw-dir /var/tmp/platform-tracing-reference-lab-artifacts/20260614-local-jfr-finalize-1 \
  --evidence-dir evidence/reference-local/gentoo-local-reference-lab-v1/20260614-local-jfr-finalize-1 \
  --scenario S0
```

Raw `.jfr` → `/var/tmp/platform-tracing-reference-lab-artifacts/<runId>/` (gitignored).  
Compact `jfr-summary.json` only in repo.

## Valid JFR artifact criteria

- `fileSizeBytes` > 0
- `jfr summary <file>` succeeds on host
- sha256 recorded in compact summary
- `storageRef` points to external path (not repo)
- `parserStatus` != failed
- `finalizationMode=durationBound` for this lab strategy

## Evidence rules

- `status=collected` when parseable and summarized
- Remove `jfrDetailedGcSummary` from `missingMetrics` only when GC pause data actually extracted
- `w004Eligible=false`, `labTier=LOCAL_REFERENCE_LAB`, always non-authoritative
