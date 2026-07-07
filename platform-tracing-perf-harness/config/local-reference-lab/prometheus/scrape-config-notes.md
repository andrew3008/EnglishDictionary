# kind local lab — minimal Prometheus scrape notes (PR-9H-E2L-METRICS)

Apply only after human approval. Not committed as auto-applied manifest (values vary by kind node IP).

## Required series (W-004 rehearsal — local only)

```promql
# CPU cores (replace pod)
rate(container_cpu_usage_seconds_total{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}[5m])

# CFS throttling ratio
rate(container_cpu_cfs_throttled_periods_total{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}[5m])
/
rate(container_cpu_cfs_periods_total{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}[5m])

# Working set
container_memory_working_set_bytes{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}

# RSS secondary
container_memory_rss{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}
```

Full list: `config/local-reference-lab/metrics/local-promql-queries.md`

## Collector self-metrics (works without cAdvisor)

Static scrape target example (pod IP workaround):

```yaml
scrape_configs:
  - job_name: perf-harness-collector
    static_configs:
      - targets: ['REPLACE_COLLECTOR_POD_IP:8888']
    metrics_path: /metrics
```

Resolve IP:

```bash
kubectl get pods -n platform-tracing-reference-lab -l app=perf-harness-collector \
  -o jsonpath='{.items[0].status.podIP}'
```

Or use `scripts/local-reference-lab/collect-local-collector-summary.sh` (no Prometheus).

## kind kubelet / cAdvisor

kind exposes kubelet on the control-plane container. kube-prometheus-stack configures this automatically. Manual static config is fragile across `kind delete cluster`.

## prometheus-values-kind.yaml sketch

See `prometheus-values-kind.yaml` for Helm value overrides (disable Grafana persistence, reduce retention, single replica).
