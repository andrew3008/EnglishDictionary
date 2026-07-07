# Prometheus / cAdvisor for local kind lab (PR-9H-E2L / PR-9H-E2L-METRICS)

kind nodes run inside Docker on Gentoo. **2026-06-14 diagnostics:** Prometheus **not installed** — primary root cause for missing CPU/RSS/CFS metrics.

See **`prometheus/README.md`** and **`metrics/local-promql-queries.md`** for PR-9H-E2L-METRICS scaffolding.

Metrics options:

1. **kube-prometheus-stack** — requires Helm + approval; full stack. See `prometheus/prometheus-values-kind.yaml`.
2. **Collector direct scrape** — `scripts/local-reference-lab/collect-local-collector-summary.sh` (no Prom required; pod IP).
3. **cAdvisor on Docker host** — query via Gentoo Prometheus if already deployed (not assumed).
4. **Missing metrics** — if unavailable, record in `caveats.missingMetrics`:
   - `container_cpu_usage_seconds_total`
   - `container_cpu_cfs_throttled_periods_total`
   - `container_memory_working_set_bytes`
   - collector self-metrics on `:8888` (available via direct scrape)

Local lab may validate k6 + pipeline wiring without full Prometheus. Do **not** fabricate CPU/RSS values.
