# PR-9H-E2L-FULL — Local lab full metrics runbook

**LOCAL_REFERENCE_LAB** — non-authoritative. Does not close W-004.

## Classification

| Field | Value |
|-------|-------|
| evidenceTier | REFERENCE |
| labTier | LOCAL_REFERENCE_LAB |
| w004Eligible | **false** |
| nonAuthoritative | **true** |

## Phase 1 — Context verification

```bash
export LAB_KUBECTL=/usr/local/bin/kubectl-v136
export LAB_KUBECTX=kind-platform-tracing-reference-lab
export REPO_HARNESS=/path/to/platform-tracing-perf-harness

kubectl config current-context   # must be kind-platform-tracing-reference-lab
kubectl get pods -n platform-tracing-reference-lab
```

Stop with `BLOCKED_WRONG_KUBE_CONTEXT` if not local kind lab.

## Phase 2 — Install Prometheus

```bash
scripts/local-reference-lab/install-prometheus-kind.sh --dry-run
scripts/local-reference-lab/install-prometheus-kind.sh --execute
```

Values: `config/local-reference-lab/prometheus/values-kind.yaml`

Verify (Gentoo: use in-cluster curl — host curl to pod IP often times out):

```bash
export LAB_PROM_URL=http://<prometheus-pod-ip>:9090
scripts/local-reference-lab/query-required-metrics.sh perf-harness-app
```

## Phase 3 — JFR + deploy

```bash
export LAB_JFR_ENABLED=true
# deploy-perf-app-lab.sh appends StartFlightRecording + /tmp/jfr mount
```

Or: `patch-local-jfr-startup.sh --execute`

## Phase 4 — Full S0/S1/S4 rerun

```bash
export LAB_TARGET_RPS=20
export LAB_WARMUP=1m
export LAB_STEADY=3m
export LAB_RUN_ID=20260614-local-metrics-full-1

scripts/local-reference-lab/run-local-metrics-full.sh --execute
# Gentoo self-contained: build/local-reference-lab/run-full-local-metrics-gentoo.sh
```

Per scenario collect:

- k6-summary.json
- prometheus-compact-summary.json (in-cluster Prom queries)
- collector-compact-summary.json
- jfr-summary.json (metadata only; raw .jfr external)

## Phase 5 — Evidence assembly

Copy compact files to:

`evidence/reference-local/gentoo-local-reference-lab-v1/<runId>/{S0,S1,S4}/`

Run root: `reference-report.md`, `README.md`

## Caveats

- kind ClusterIP/DNS broken — pod IP workarounds
- Host Prometheus port-forward unreliable on Gentoo
- All evidence: `w004Eligible=false`, W-004 **OPEN**

## PR-9H-E2L-METRICS (legacy diagnostics)

See prior sections in git history for pre-Prometheus diagnostic flow.
