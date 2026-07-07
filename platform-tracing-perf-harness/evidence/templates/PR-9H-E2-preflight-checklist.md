# PR-9H-E2 pre-flight checklist — first official REFERENCE run

Complete **before** executing S0/S1/S4 on Kubernetes. E2 does not resolve W-004.

## Environment

- [ ] Namespace assigned (`REPLACE_NAMESPACE`)
- [ ] App CPU/memory requests and limits set and documented
- [ ] Collector CPU/memory requests and limits set and documented
- [ ] Reference sink CPU/memory requests and limits set and documented
- [ ] Reference sink image approved and reachable (`REPLACE_REFERENCE_SINK_IMAGE`)
- [ ] App image includes OTel agent + extension (`REPLACE_PERF_APP_IMAGE_TAG`)
- [ ] Collector image pinned (`REPLACE_COLLECTOR_IMAGE`)

## k6 / load

- [ ] k6 scenarios ConfigMap generated: `prepareK6ReferenceConfigMap`
- [ ] `reference-steady.js` mounted in k6 Job
- [ ] TARGET_RPS calibrated (short S0 saturation probe)
- [ ] TARGET_RPS pinned (~60–70% of S0 saturation)
- [ ] **Same TARGET_RPS for S0, S1, S4** (no RPS sweep in E2)

## Metrics scrape (Prometheus / cAdvisor)

- [ ] `container_cpu_usage_seconds_total`
- [ ] `container_cpu_cfs_periods_total`
- [ ] `container_cpu_cfs_throttled_periods_total`
- [ ] `container_memory_working_set_bytes`
- [ ] Collector self-metrics on `:8888`:
  - `otelcol_exporter_queue_size`
  - `otelcol_exporter_queue_capacity`
  - `otelcol_exporter_send_failed_spans`
  - `otelcol_receiver_refused_spans`

## JFR

- [ ] JFR started at JVM startup (`-XX:StartFlightRecording`, `jfrStartMode=startup`)
- [ ] JFR settings **symmetric** across S0 and test scenarios
- [ ] External JFR artifact store named (`REPLACE_ARTIFACT_STORE`)
- [ ] sha256 capture process defined

## Provenance / governance

- [ ] `gitCommit` resolved and **not** `unknown`
- [ ] Budgets acknowledged as **provisional** (SRE-approved not required for E2 capture)
- [ ] Agreement: **E2 does not resolve W-004**
- [ ] Agreement: **E2 does not reclassify W-011/W-012 by default**
- [ ] Agreement: no raw multi-MB dumps committed to git

## Scenarios (E2 minimum)

- [ ] S0 — control baseline (required)
- [ ] S1 — tracing on, validation disabled (required)
- [ ] S4 — combined production mode (required)
- [ ] S2/S3 — optional if time permits
- [ ] S5 — deferred (diagnostic)
- [ ] S6 — deferred (soak PR-9H-F)
