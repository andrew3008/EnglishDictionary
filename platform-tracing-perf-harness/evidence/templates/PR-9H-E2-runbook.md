# PR-9H-E2 runbook — first official Kubernetes REFERENCE run

Manual cluster execution. Gradle does **not** auto-run kubectl. See `config/k8s/README.md`.

**E2 cannot:** resolve W-004, set `w004Eligible=true`, claim production readiness, or reclassify W-011/W-012 by default.

---

## Step 1 — Prepare environment

1. Complete `PR-9H-E2-preflight-checklist.md`
2. Set all `REPLACE_*` values in `config/k8s/*.yaml`
3. Name external artifact store for raw JFR (`storageRef` + sha256)
4. Resolve real `gitCommit` on the branch used for the run

## Step 2 — Calibrate TARGET_RPS

1. Deploy S0 (baseline) only
2. Short calibration k6 run at increasing RPS (lab-only; not committed as official evidence)
3. Find approximate S0 saturation
4. Pin `TARGET_RPS` at ~60–70% of saturation
5. Use **identical** `TARGET_RPS` for official S0, S1, S4 runs

## Step 3 — Generate k6 ConfigMap

```text
./gradlew :platform-tracing-perf-harness:prepareK6ReferenceConfigMap
# Output: build/k8s/k6-scenarios-configmap.yaml — substitute REPLACE_NAMESPACE, then apply
```

## Step 4 — Apply manifests (order)

```text
kubectl apply -f namespace.yaml
kubectl apply -f reference-sink-deployment.yaml
kubectl apply -f reference-sink-service.yaml
kubectl apply -f otel-collector-configmap.yaml
kubectl apply -f otel-collector-deployment.yaml
kubectl apply -f otel-collector-service.yaml
kubectl apply -f perf-app-configmap.yaml
kubectl apply -f perf-app-deployment.yaml
kubectl apply -f perf-app-service.yaml
kubectl apply -f build/k8s/k6-scenarios-configmap.yaml
```

Wait for perf app readiness: `/perf/health`

## Step 5 — Execute minimum scenarios

For each scenario (S0, S1, S4):

1. Update perf-app ConfigMap spring profile + redeploy if needed
2. Update k6 Job env (`REPLACE_SCENARIO`, `REPLACE_ENDPOINT_PATH`, `REPLACE_SPRING_PROFILE`)
3. Delete prior k6 Job if exists
4. `kubectl apply -f k6-job.yaml`
5. Collect k6 summary from Job pod artifacts volume or logs

| Scenario | Spring profile | Endpoint (example) |
|----------|----------------|-------------------|
| S0 | `s0-baseline` | `/perf/work` |
| S1 | `s1-tracing` | `/perf/work` |
| S4 | `s4-production` | `/perf/work` |

## Step 6 — Optional scenarios

- **S2** — validation lenient valid (recommended)
- **S3** — validation lenient missing attrs (recommended)

## Step 7 — Deferred

- **S5** — strict diagnostic (isolated; not E2 gate)
- **S6** — soak/RSS trend (PR-9H-F)

## Step 8 — Collect metrics

Per scenario steady window (≥ 10m after warmup):

| Source | Output |
|--------|--------|
| k6 | `k6-summary.json` (compact) |
| Prometheus | `prometheus-compact-summary.json` (aggregates only) |
| JFR | `jfr-summary.json` + raw `.jfr` to external store |
| Commands | `command.txt` |
| Provenance | agent/extension/collector versions, kernel, cgroup |

Compute and record in summary:

- `cpu.per1kRps` = `coresRateAvg / achievedRpsAvg * 1000`
- `throttleRatioPct` from CFS metrics
- `memory.workingSetBytesAvg` from cAdvisor
- `latency.p95Ms`, `p99Ms` from k6 steady window

## Step 9 — Assemble summaries

Working directory: `build/perf-results/reference/<profileId>/<runId>/`

Create skeleton (no fake metrics):

```text
./gradlew :platform-tracing-perf-harness:perfReferenceCreateSummarySkeleton \
  -PperfProfileId=k8s-reference-lab-v1 \
  -PperfScenario=S4 \
  -PperfRunId=20260614-160000 \
  -PperfNamespace=platform-tracing-perf \
  -PperfTargetRps=300 \
  -PperfOutputDir=build/perf-results/reference/k8s-reference-lab-v1/20260614-160000
```

Fill metrics from captures. Copy compact committed set to:

`evidence/reference/<profileId>/<runId>/`

Defaults:

- `w004Eligible: false`
- `nonAuthoritative: true`
- `nonAuthoritativeReasons`: `singleRunOnly`, `provisionalBudgetOnly`

## Step 10 — Validate

```text
./gradlew :platform-tracing-perf-harness:perfReferenceValidateConfig
./gradlew :platform-tracing-perf-harness:test
```

Confirm:

- No forbidden raw extensions under `evidence/reference/`
- Schema validates with `w004Eligible=false`
- W-004 remains OPEN in Warning Register

## Step 11 — Warning Register

Update W-004 note: first REFERENCE evidence captured (if run completed). W-011/W-012 unchanged unless reproducible macro issue documented.
