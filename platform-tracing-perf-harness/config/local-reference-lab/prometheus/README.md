# Prometheus for Gentoo LOCAL_REFERENCE_LAB (PR-9H-E2L-METRICS)

**Local lab only.** Does not modify official `config/k8s/` templates.

## Current status (2026-06-14 PR-9H-E2L-FULL)

| Item | Status |
|------|--------|
| Prometheus installed in kind cluster | **YES** (Helm `local-prom`, ns `monitoring`) |
| cAdvisor `container_*` metrics | **PARTIAL** — verified via in-cluster Prom query (3 series in lab ns) |
| Collector `otelcol_*` on `:8888` | **CONFIGURED** — additionalScrapeConfigs + pod IP fallback |
| JFR startup | **CONFIGURED** — `LAB_JFR_ENABLED=true` in deploy-perf-app-lab.sh |
| Host curl to Prom pod IP | **FAIL** — use in-cluster curl Jobs |
| Committed compact evidence | **PENDING** — Gentoo artifact pull |

## Scripts (PR-9H-E2L-FULL)

| Script | Purpose |
|--------|---------|
| `install-prometheus-kind.sh --execute` | Helm install/upgrade kube-prometheus-stack |
| `query-required-metrics.sh` | Verify required PromQL families |
| `port-forward-local-prometheus.sh` | Port-forward (may fail on Gentoo host) |
| `collect-local-prometheus-summary.sh` | Compact JSON summary |
| `run-local-metrics-full.sh --execute` | Full S0/S1/S4 + metrics orchestration |

## Installation (approval granted for local lab)

```bash
export LAB_KUBECTL=/usr/local/bin/kubectl-v136
export LAB_KUBECTX=kind-platform-tracing-reference-lab
export REPO_HARNESS=/path/to/platform-tracing-perf-harness
scripts/local-reference-lab/install-prometheus-kind.sh --execute
```

Values: `config/local-reference-lab/prometheus/values-kind.yaml`

## Previous status (PR-9H-E2L-METRICS diagnostics)

| Item | Status |
|------|--------|
| Prometheus installed in kind cluster | **NO** (before PR-9H-E2L-FULL) |

## Root cause

The PR-9H-E2L local lab deployed sink → collector → perf-app + k6 only. No metrics stack was installed. cAdvisor/kubelet metrics are not queryable without Prometheus (or equivalent) scraping the kind node/kubelet.

## kind-specific notes

- kind runs the control-plane inside Docker on Gentoo.
- kubelet/cAdvisor endpoints are on the kind node container, not exposed by default.
- **kube-prometheus-stack** or **prometheus-operator** is the usual path for `container_*` series.
- **ClusterIP/DNS is broken** on this Gentoo lab — prefer pod IP for in-cluster probes until PR-9H-E2L-Network.

## Installation options (human approval required)

Do **not** run automatically from CI or default scripts.

### Option A — kube-prometheus-stack (Helm)

```bash
# APPROVAL REQUIRED — on Gentoo with helm
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install local-prom prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace \
  -f config/local-reference-lab/prometheus/prometheus-values-kind.yaml
```

Then port-forward:

```bash
LAB_KUBECTL=/usr/local/bin/kubectl-v136 \
  scripts/local-reference-lab/port-forward-local-prometheus.sh --execute
export LAB_PROM_URL=http://127.0.0.1:9090
scripts/local-reference-lab/collect-local-prometheus-summary.sh 20260614-local-metrics-1
```

### Option B — Minimal static Prometheus (manifest)

See `scrape-config-notes.md` for a minimal ConfigMap scrape config targeting:

- kubelet/cAdvisor (kind node)
- collector pod IP `:8888` (static target — workaround for broken DNS)
- perf-app actuator (if `/actuator/prometheus` enabled)

Apply only with `--execute` approval.

## Scripts

| Script | Purpose |
|--------|---------|
| `diagnose-local-metrics.sh` | Read-only inventory |
| `port-forward-local-prometheus.sh` | Port-forward if Prom exists |
| `query-local-prometheus.sh` | Ad-hoc PromQL |
| `collect-local-prometheus-summary.sh` | Compact JSON (or missing placeholder) |
| `collect-local-collector-summary.sh` | Direct collector scrape (no Prom) |

All evidence: `labTier=LOCAL_REFERENCE_LAB`, `w004Eligible=false`.
