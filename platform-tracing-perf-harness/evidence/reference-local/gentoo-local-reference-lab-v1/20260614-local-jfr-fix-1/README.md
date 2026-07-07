# LOCAL_REFERENCE_LAB — PR-9H-E2L-JFR-fix / PR-9H-E2L-JFR-summary

**runId:** `20260614-local-jfr-fix-1`  
**Status:** `COMPLETED_PARTIAL_JFR_ARTIFACT_PARSE_NON_AUTHORITATIVE`  
**labTier:** `LOCAL_REFERENCE_LAB` | **w004Eligible:** `false`

JFR startup capture fixed; S0/S1/S4 rerun with non-empty JFR artifacts (1.9–2.7 MB each). PR-9H-E2L-JFR-summary attempted parse via `jfr` CLI — all artifacts **locked stream state** (continuous recording copied without finalization; JRE has no `jcmd`). Detailed GC summary remains unavailable.

See `reference-report.md`.
