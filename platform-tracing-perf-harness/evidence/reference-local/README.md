# Local reference lab evidence (PR-9H-E2L)

Committed **compact** summaries from **LOCAL_REFERENCE_LAB** runs only.

- **Not** official SRE/pre-prod evidence
- **Cannot** close W-004
- **Never** place local lab artifacts under `evidence/reference/` (official path)

Layout: `evidence/reference-local/<profileId>/<runId>/`

Required fields in `reference-summary.json`:

- `labTier: LOCAL_REFERENCE_LAB`
- `w004Eligible: false`
- `nonAuthoritative: true`
- `nonAuthoritativeReasons`: must include `localEnvironment`

Raw JFR/k6/Prometheus dumps: external path (`file:///...` on Gentoo) — not committed.

## Metrics diagnostics (PR-9H-E2L-METRICS)

- Collector compact summaries (`collector-compact-summary.json`) may be committed when produced by `collect-local-collector-summary.sh`.
- Prometheus compact summaries only when real Prom is installed — otherwise placeholder with `missingMetrics`.
- JFR: metadata-only `jfr-summary.json` (sha256, size); raw `.jfr` never committed.
- All metrics evidence remains `w004Eligible=false`.

## PR-9H-E2L-FULL (2026-06-15)

- Run: `20260614-local-metrics-full-1` — k6/Prom/collector; JFR missing

## PR-9H-E2L-JFR-fix (2026-06-15)

- Run: `20260614-local-jfr-fix-1` — JFR collected (1.9–2.7 MB/scenario)
- Status: `COMPLETED_JFR_LOCAL_CAPTURE_AND_RERUN_NON_AUTHORITATIVE`
- Artifacts **unparseable** (locked stream) — superseded by finalize run

## PR-9H-E2L-JFR-summary (2026-06-15)

- Parser scaffolding; confirmed `locked_stream_state` on fix-1 artifacts

## PR-9H-E2L-JFR-finalize (2026-06-15)

- Run: `20260614-local-jfr-finalize-1` — duration-bound JFR (2m smoke / 7m scenarios)
- Status: `COMPLETED_PARSEABLE_JFR_AND_RERUN_NON_AUTHORITATIVE`
- GC pause summaries extracted; `jfrDetailedGcSummary` cleared where parsed
- Still `w004Eligible=false`; W-004 OPEN
