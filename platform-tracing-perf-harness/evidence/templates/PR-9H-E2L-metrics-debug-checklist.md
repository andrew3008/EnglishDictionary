# PR-9H-E2L-FULL pre-flight checklist

**LOCAL_REFERENCE_LAB** — not official SRE/pre-prod.

## Context verification

- [ ] SSH target is Gentoo laptop (192.168.100.70)
- [ ] `kubectl config current-context` = `kind-platform-tracing-reference-lab`
- [ ] Namespace `platform-tracing-reference-lab` exists
- [ ] Not connected to production cluster

## Prometheus / cAdvisor

- [ ] `install-prometheus-kind.sh --execute` completed (or release `local-prom` exists)
- [ ] Prometheus pods Running in `monitoring` namespace
- [ ] In-cluster Prom query returns `container_cpu_usage_seconds_total` for lab namespace
- [ ] If host curl fails: use in-cluster curl Jobs (documented)
- [ ] No fabricated CPU/RSS in committed evidence

## Collector self-metrics

- [ ] additionalScrapeConfigs for `perf-harness-collector` applied
- [ ] `otelcol_*` queryable via Prom or pod IP fallback
- [ ] `backpressureEvidenceValid=false` for local debug sink

## JFR

- [ ] `LAB_JFR_ENABLED=true` or `patch-local-jfr-startup.sh --execute`
- [ ] `/tmp/jfr` emptyDir mounted
- [ ] Post-run `jcmd JFR.dump` + `kubectl cp` to artifact dir
- [ ] Raw `.jfr` **not** committed to git

## Scenarios S0 / S1 / S4

- [ ] TARGET_RPS=20, warmup=1m, steady=3m
- [ ] k6 uses perf-app pod IP (DNS workaround)
- [ ] Per-scenario: k6, prom, collector, jfr summaries

## Evidence governance

- [ ] runId e.g. `20260614-local-metrics-full-1`
- [ ] All summaries: `labTier=LOCAL_REFERENCE_LAB`, `w004Eligible=false`
- [ ] `nonAuthoritativeReasons`: localEnvironment, singleRunOnly, provisionalBudgetOnly
- [ ] `missingMetrics` listed for any gap
- [ ] W-004 remains **OPEN**
- [ ] W-011/W-012 unchanged

## PR-9H-E2L-METRICS (historical)

Pre-FULL diagnostics expected Prometheus **not installed** — superseded after FULL install.
