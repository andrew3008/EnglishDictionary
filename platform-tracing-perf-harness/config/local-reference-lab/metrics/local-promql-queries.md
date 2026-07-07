# Local PromQL reference — LOCAL_REFERENCE_LAB (PR-9H-E2L-METRICS)

Replace `REPLACE_POD` with perf-app pod name prefix (e.g. `perf-harness-app`).

Namespace: `platform-tracing-reference-lab`

## cAdvisor / kubelet (requires Prometheus)

### CPU cores
```promql
rate(container_cpu_usage_seconds_total{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}[5m])
```

### CFS throttling ratio
```promql
rate(container_cpu_cfs_throttled_periods_total{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}[5m])
/
rate(container_cpu_cfs_periods_total{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}[5m])
```

### Working set (primary RSS evidence)
```promql
container_memory_working_set_bytes{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}
```

### RSS secondary
```promql
container_memory_rss{namespace="platform-tracing-reference-lab", container!="", pod=~"REPLACE_POD.*"}
```

## Collector self-metrics (pod IP :8888 or Prometheus scrape)

```promql
otelcol_exporter_queue_size{data_type="traces"}
otelcol_exporter_queue_capacity{data_type="traces"}
otelcol_exporter_send_failed_spans
otelcol_exporter_enqueue_failed_spans
otelcol_processor_dropped_spans
otelcol_receiver_accepted_spans
otelcol_receiver_refused_spans
```

Direct collection without Prometheus: `scripts/local-reference-lab/collect-local-collector-summary.sh`

## perf-app Spring Actuator

```promql
# Only if /actuator/prometheus returns 200 (currently 404 in local lab — Micrometer registry)
```

Probe: `curl http://<perf-app-pod-ip>:8080/actuator/prometheus`

## Normalization (REFERENCE methodology — local rehearsal only)

```
cpu.per1kRps = (cpu_cores_avg / achievedRpsAvg) * 1000
```

Do not fabricate if Prometheus CPU series missing.
