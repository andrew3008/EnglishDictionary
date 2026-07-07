# Local Kubernetes REFERENCE Lab (PR-9H-E2L)

**LOCAL_REFERENCE_LAB** — rehearses PR-9H-E2 mechanics on Gentoo. **Not** official SRE/pre-prod evidence. **Cannot** close W-004.

## Target host

Gentoo reference laptop (default lab IP documented in `local-reference-lab.env.example`). SSH key auth required for mutating setup. Docker remote (`DOCKER_HOST=tcp://<lab>:2375`) supports read-only inventory from Windows.

## Quick start

```bash
# Phase 1 — read-only inventory (on Gentoo via SSH, or partial via Docker remote)
./inventory-gentoo.sh --read-only

# Phase 2 — after human approval and backend choice (kind recommended if Docker healthy)
./setup-kind-lab.sh --dry-run          # preview
./setup-kind-lab.sh --execute          # requires approval; creates kind cluster

# Deploy lab (after images built)
./build-local-images.sh --dry-run
./deploy-local-reference-lab.sh --dry-run

# Run scenarios (after calibration)
./run-local-reference-scenarios.sh --dry-run --scenarios S0,S1,S4

# Collect summaries
./collect-local-reference-summary.sh --scenario S4 --run-id <runId>
```

## Metrics diagnostics (PR-9H-E2L-METRICS)

```bash
export LAB_KUBECTL=/usr/local/bin/kubectl-v136   # Gentoo

./diagnose-local-metrics.sh                      # read-only
./collect-local-collector-summary.sh <runId>     # otelcol_* via pod IP (no Prom)
./patch-local-jfr-startup.sh --dry-run           # JFR overlay preview
# Prometheus install: config/local-reference-lab/prometheus/README.md (approval)
```

See `evidence/templates/PR-9H-E2L-metrics-debug-runbook.md`.

## Safety

- All mutating scripts require explicit `--execute` or `--approve` flags.
- Cleanup requires `--execute --confirm-delete`.
- No sudo, emerge, or curl|sh in automated paths.
- See `evidence/templates/PR-9H-E2L-local-reference-lab-runbook.md`.

## Evidence

- Working: `build/perf-results/local-reference-lab/<profileId>/<runId>/`
- Committed compact: `evidence/reference-local/<profileId>/<runId>/` (never under `evidence/reference/`)
