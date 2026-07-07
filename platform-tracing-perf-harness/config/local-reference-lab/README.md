# Local reference lab overlays (PR-9H-E2L)

Gentoo-specific values live here and in `scripts/local-reference-lab/local-reference-lab.env` — **not** in official `config/k8s/` templates.

| Constant | Value |
|----------|-------|
| profileId | `gentoo-local-reference-lab-v1` |
| namespace | `platform-tracing-reference-lab` |
| labTier | `LOCAL_REFERENCE_LAB` |
| kind cluster | `platform-tracing-reference-lab` |
| kubectl context | `kind-platform-tracing-reference-lab` |

## Backend

**Recommended: kind** on Gentoo when Docker is healthy (8 CPU, 32 GiB, cgroup v2 verified via Docker remote).

**Alternative: k3s** — manual install only (requires sudo approval).

## Deploy flow

1. `./gradlew :platform-tracing-perf-harness:prepareK6ReferenceConfigMap`
2. `scripts/local-reference-lab/inventory-gentoo.sh --read-only`
3. After approval: `setup-kind-lab.sh --execute`
4. `build-local-images.sh --execute` (on Gentoo or via Docker remote)
5. `deploy-local-reference-lab.sh --execute`
6. Calibrate `LAB_TARGET_RPS`, then `run-local-reference-scenarios.sh --execute`

See `evidence/templates/PR-9H-E2L-local-reference-lab-runbook.md`.

## Metrics diagnostics (PR-9H-E2L-METRICS)

| Path | Purpose |
|------|---------|
| `prometheus/` | Prom install notes, kind Helm values |
| `jfr/` | JFR startup overlay |
| `metrics/local-promql-queries.md` | Required PromQL |
| `../scripts/local-reference-lab/diagnose-local-metrics.sh` | Read-only diagnostics |

**2026-06-14 finding:** Prometheus not installed; collector metrics OK via pod IP; JFR not configured.
