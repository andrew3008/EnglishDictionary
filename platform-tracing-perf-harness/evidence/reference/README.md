# Committed REFERENCE evidence summaries (PR-9H-E2+)

This directory holds **compact, committed** REFERENCE tier evidence only.
It is the official location for W-004 macro evidence summaries after Kubernetes runs.

## What belongs here

Per run: `evidence/reference/<profileId>/<runId>/`

| File | Purpose |
|------|---------|
| `reference-summary.json` | Schema-valid summary (`w004Eligible=false` for E2 first run) |
| `reference-report.md` | Filled report from `evidence/templates/PR-9H-reference-run-report-template.md` |
| `command.txt` | Exact kubectl/k6/PromQL commands used |
| `checksums.txt` | sha256 of external raw artifacts |
| `k6-summary.json` | Compact k6 export (not full raw trace dump) |
| `prometheus-compact-summary.json` | Steady-window aggregates only |
| `jfr-summary.json` | GC/alloc extract (not raw `.jfr`) |

## What must NOT be committed

Raw dumps live in **external artifact storage** only:

- `*.jfr`, `*.hprof`, `*.heapdump`, `*.dump`
- `*prometheus-raw*`, `*k6-raw*`
- `*.tar`, `*.tar.gz`, `*.zip`

Reference raw files by `artifacts.jfr.storageRef` + `sha256` in `reference-summary.json`.

Working/generated files during a run stay under:

`build/perf-results/reference/<profileId>/<runId>/` (gitignored via `build/`)

## E2 first run defaults

- `w004Eligible: false`
- `nonAuthoritative: true`
- `nonAuthoritativeReasons`: include `singleRunOnly`, `provisionalBudgetOnly`
- Do not copy SMOKE artifacts here (`evidenceTier: SMOKE` is invalid for this tree)

## Assembly

1. Run cluster execution per `evidence/templates/PR-9H-E2-runbook.md`
2. Create skeleton: `./gradlew :platform-tracing-perf-harness:perfReferenceCreateSummarySkeleton ...`
3. Fill metrics manually from compact captures — **do not invent values**
4. Validate: `./gradlew :platform-tracing-perf-harness:test` (schema tests)
