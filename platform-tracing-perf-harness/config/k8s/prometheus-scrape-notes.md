# Prometheus / cAdvisor scrape notes (REFERENCE tier)

## Required signals for W-004

| Signal | Source | Metric examples |
|--------|--------|-----------------|
| CPU | cAdvisor / kubelet | `container_cpu_usage_seconds_total` |
| RSS / working-set | cAdvisor | `container_memory_working_set_bytes` |
| App metrics | `/actuator/prometheus` | JVM, Tomcat, platform export queue if exposed |
| Collector queue | Collector `:8888/metrics` | `otelcol_*` queue/drop counters |

## App scrape

Prometheus annotations on `perf-harness-app` Deployment (see `perf-app-deployment.yaml`):

- path: `/actuator/prometheus`
- port: `8080`

## cAdvisor

Typically scraped by cluster Prometheus via kubelet/cAdvisor job — **not** duplicated in these templates.

Document node pool / cgroup path in reference `summary.json` → `environment.nodePool`.

## ServiceMonitor (optional)

If Prometheus Operator is available, use `servicemonitor-template.yaml` after substituting `REPLACE_NAMESPACE`.

## Snapshot policy

- Capture Prometheus range queries at steady-state window (not single instant)
- Store compact JSON summary in reference run dir — **do not commit raw multi-MB dumps**
- Raw snapshots: `build/perf-results/reference/<profileId>/<runId>/prometheus/` (gitignored)
