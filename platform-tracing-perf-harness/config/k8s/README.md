# Kubernetes REFERENCE tier templates (PR-9H-E / PR-9H-E1)

**Not production manifests.** Parameterized templates for official W-004 macro evidence runs.

## Before apply

Substitute placeholders (search `REPLACE_`):

| Placeholder | Example | Notes |
|-------------|---------|-------|
| `REPLACE_NAMESPACE` | `platform-tracing-perf` | Or use existing perf namespace |
| `REPLACE_IMAGE_REGISTRY` | `registry.example.com/platform-tracing` | Perf app + collector images |
| `REPLACE_PERF_APP_IMAGE_TAG` | `perf-harness:git-sha` | From `bootJar` + agent extension build |
| `REPLACE_COLLECTOR_IMAGE` | `otel/opentelemetry-collector-contrib:0.154.0` | Pin version |
| `REPLACE_REFERENCE_SINK_ENDPOINT` | `http://perf-harness-reference-sink:4318` | Backpressure-capable OTLP sink |
| `REPLACE_REFERENCE_SINK_IMAGE` | lab throttled OTLP sink image | See `reference-sink-*.yaml` |
| `REPLACE_TARGET_RPS` | `300` (same for S0/S1/S4) | Pin before E2 |
| `REPLACE_PROFILE_ID` | `k8s-reference-lab-v1` | Committed evidence profile id |
| `REPLACE_SCENARIO` | `S4` | S0–S6 (see scenario-matrix.md) |
| `REPLACE_SPRING_PROFILE` | `s4-production` | Maps to ConfigMap |

## Collector backpressure (PR-9H-E1)

The collector **must not** use debug/logging as the sole traces exporter for W-004 evidence.

- Primary exporter: `otlphttp/reference_sink` with `sending_queue` enabled
- `backpressureEvidenceValid=true` only when reference sink is active
- Debug/logging exporter invalidates queue/backpressure evidence

Required collector self-metrics (Prometheus :8888):

- `otelcol_exporter_queue_size`
- `otelcol_exporter_queue_capacity`
- `otelcol_exporter_send_failed_spans`
- `otelcol_exporter_enqueue_failed_spans`
- `otelcol_processor_dropped_spans`
- `otelcol_receiver_refused_spans`

## Resource limits (placeholders)

Templates include **requests/limits placeholders** — set per lab policy before official run:

- Perf app: `REPLACE_APP_CPU_REQUEST`, `REPLACE_APP_CPU_LIMIT`, `REPLACE_APP_MEM_REQUEST`, `REPLACE_APP_MEM_LIMIT`
- Collector: `REPLACE_COLLECTOR_CPU_*`, `REPLACE_COLLECTOR_MEM_*`
- Reference sink: `REPLACE_SINK_*`
- k6 Job: `REPLACE_K6_CPU_*`, `REPLACE_K6_MEM_*`

## Generate k6 ConfigMap (PR-9H-E2)

```text
./gradlew :platform-tracing-perf-harness:prepareK6ReferenceConfigMap
# Output: build/k8s/k6-scenarios-configmap.yaml
kubectl apply -f build/k8s/k6-scenarios-configmap.yaml   # after REPLACE_NAMESPACE
```

Template stub: `k6-scenarios-configmap.yaml` (validated; do not apply stub directly).

## Apply order

```text
kubectl apply -f namespace.yaml                    # if namespace not externally managed
kubectl apply -f reference-sink-deployment.yaml    # backpressure sink (PR-9H-E1)
kubectl apply -f reference-sink-service.yaml
kubectl apply -f otel-collector-configmap.yaml
kubectl apply -f otel-collector-deployment.yaml
kubectl apply -f otel-collector-service.yaml
kubectl apply -f perf-app-configmap.yaml
kubectl apply -f perf-app-deployment.yaml
kubectl apply -f perf-app-service.yaml
kubectl apply -f build/k8s/k6-scenarios-configmap.yaml   # generated; see prepareK6ReferenceConfigMap
# After SUT ready + profile validated:
kubectl apply -f k6-job.yaml                     # one-shot; TARGET_RPS same for S0/S1/S4
```

## Metrics

- App: `/actuator/prometheus` (read-only; mutation disabled)
- cAdvisor/kubelet: `container_memory_working_set_bytes` for RSS evidence
- CPU: `rate(container_cpu_usage_seconds_total[steady_window])`
- See `prometheus-scrape-notes.md` and optional `servicemonitor-template.yaml`

## Security

- No JMX Service exposure
- No Actuator write/mutation endpoints (`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus`)
- No secrets in templates — inject via cluster secret store

## Evidence contract

- `evidence/templates/reference-summary.schema.json`
- `evidence/templates/W-004-resolution-checklist.md`
- `config/profiles/reference-scenario-matrix.md`

## Gradle (planning only)

```text
./gradlew :platform-tracing-perf-harness:perfReferencePlan -PperfProfileId=k8s-reference-lab-v1 -PperfScenario=S4
./gradlew :platform-tracing-perf-harness:perfReferenceValidateConfig
./gradlew :platform-tracing-perf-harness:perfReferenceRun   # refuses without -PperfReferenceConfirmed=true
```

Official execution: **PR-9H-E2** (after SRE/architecture approves hardened contract and TARGET_RPS).
