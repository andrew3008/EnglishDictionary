# PR-9H — Macro/load/RSS evidence harness

## PR-9H-C (harness skeleton + SMOKE tier)

### Architecture decision

Hybrid **SMOKE + REFERENCE** tiers, packaged as `platform-tracing-perf-harness`.
See [PR-9H-macro-harness-architecture-decision.md](PR-9H-macro-harness-architecture-decision.md).

### What landed

- Dedicated Gradle module (not published; no production dependency edge)
- Representative Spring Boot perf app with `/perf/*` endpoints
- k6 scenarios: `scenarios/smoke.js`, `scenarios/reference-steady.js` (skeleton)
- Scenario profiles S0–S6 (structure; not fully validated)
- Artifact conventions: `build/perf-results/smoke|reference/` (gitignored)
- Evidence template: `platform-tracing-perf-harness/evidence/templates/`
- Gradle: `perfSmoke` (optional k6), `perfReferenceRun` (stub/refuse)

### SMOKE tier

- Functional only; `evidenceTier: SMOKE`, `nonAuthoritative: true`
- Does **not** close W-004
- Does **not** reclassify W-011/W-012

### REFERENCE tier (deferred)

Kubernetes/pre-prod only (PR-9H-E+):

- enforced CPU/memory limits
- open-model k6
- Prometheus/cAdvisor working-set
- JFR with control-matched overhead
- bounded-queue OTLP collector
- soak window (PR-9H-F)

### W-004 resolution criteria (summary)

W-004 RESOLVED only when REFERENCE tier provides reproducible evidence for:

CPU overhead, RSS/working-set, p95/p99, JFR/GC, error rate, export queue/backpressure, soak stability, committed summary report.

Full checklist: `platform-tracing-perf-harness/evidence/templates/PR-9H-reference-evidence-template.md`

### Follow-up PRs

| PR | Goal |
|----|------|
| PR-9H-D | First macro smoke results + scenario refinement ✅ |
| PR-9H-E | Official Kubernetes reference run |
| PR-9H-F | Soak/RSS trend |
| PR-9H-G | W-011/W-012 classification + W-004 decision |

## PR-9H-D (first macro smoke execution + scenario refinement)

### What landed

- **k6 execution**: local k6, Docker local (`host.docker.internal`), Docker remote via `scripts/run-perf-smoke.ps1` (COPY-based image — remote-daemon safe)
- **Gradle**: `prepareK6SmokeDockerContext`, `buildK6SmokeDockerImage`, enhanced `perfSmoke` with `-PperfSmokeUseDocker`, `-PDockerHost`, `-PperfBaseUrl`, `-PperfSmokeDuration`, `-PperfSmokeTargetRps`
- **smoke.js**: hits `/perf/health`, `/perf/fast`, `/perf/validation/valid`, `/perf/validation/missing`
- **Artifacts**: `summary.json`, `k6-summary.json`, `command.txt` under `build/perf-results/smoke/<timestamp>/`
- **Schema**: `evidence/templates/smoke-summary.schema.json` + example `evidence/smoke-examples/sample-summary.json`
- **Profiles S0–S6**: refined with endpoint matrix in `config/profiles/README.md`

### How to run smoke

```text
./gradlew :platform-tracing-perf-harness:bootRun -PperfProfiles=smoke
./gradlew :platform-tracing-perf-harness:perfSmoke -PperfSmokeUseDocker=true
```

Remote lab (Gentoo Docker at 192.168.100.70:2375):

```text
./gradlew :platform-tracing-perf-harness:perfSmoke -PperfSmokeUseDocker=true \
  -PDockerHost=tcp://192.168.100.70:2375 \
  -PperfBaseUrl=http://<host-reachable-from-gentoo>:8080
```

When the Docker daemon is remote, `host.docker.internal` does not resolve — use the SUT host LAN IP
(e.g. `http://192.168.100.69:8080` from Gentoo reaching a Windows dev machine).

### PR-9H-D smoke execution (2026-06-14)

Docker k6 (remote daemon), `BASE_URL=http://192.168.100.69:8080`, 15s @ 5 RPS — all `/perf/*`
checks passed (304 requests, 0 fails). Artifacts: `build/perf-results/smoke/20260614-135849/`
(`summary.json`, `k6-summary.json`, `command.txt`). **Non-authoritative; W-004 remains OPEN.**

### SMOKE remains non-authoritative

- `evidenceTier: SMOKE`, `w004Eligible: false`
- **W-004 stays OPEN**
- No CPU/RSS/p99 gates from smoke
- Artifacts never under `evidence/reference/`

### Related JMH work

- W-003 RESOLVED (PR-9H-B2 validation JMH baseline)
- JMH does not prove pre-prod readiness (W-004 OPEN)

## PR-9H-E (REFERENCE run contract + K8s templates)

### What landed

- **K8s templates**: `config/k8s/` — perf app, bounded-queue collector, k6 Job, optional ServiceMonitor
- **Gradle**: `perfReferencePlan`, `perfReferenceValidateConfig`, guarded `perfReferenceRun` (refuses without `-PperfReferenceConfirmed=true`)
- **Evidence contract**: `reference-summary.schema.json`, W-004 checklist, reference run report template
- **Scenario matrix**: `config/profiles/reference-scenario-matrix.md` (S0–S6 official reference)
- **Example**: `evidence/reference-examples/sample-reference-summary.json` (non-authoritative)

### What did NOT land

- No official reference execution (PR-9H-E2)
- No soak/RSS trend (PR-9H-F)
- W-004 remains **OPEN**

### REFERENCE guardrails

- Output must be under `build/perf-results/reference/` — never `smoke/`
- SMOKE summaries cannot set `w004Eligible: true`
- `perfReferenceRun` refuses missing properties and refuses cluster execution in PR-9H-E

### Next steps

1. SRE/architecture review of hardened evidence contract (PR-9H-E1)
2. **PR-9H-E2** — first official Kubernetes reference run (S0, S1, S4 minimum)
3. **PR-9H-F** — soak/RSS trend
4. **PR-9H-G** — W-004 / W-011 / W-012 decision

## PR-9H-E1 (REFERENCE evidence contract hardening)

### What landed

- **Schema hardening**: `reference-summary.schema.json` with `if/then` rules — `w004Eligible=true` structurally impossible without full metrics, load validity, budgets, reproducibility
- **CPU normalization**: `cpu.per1kRps`, `throttleRatioPct` <= 5%, PromQL field
- **Load validity**: constant-arrival-rate, achievedRpsPct >= 0.95, droppedIterations = 0
- **Memory semantics**: `container_memory_working_set_bytes/cAdvisor` required for eligibility
- **JFR parity**: `jfrStartMode=startup`, symmetric settings, external storageRef + sha256
- **Collector backpressure**: otlphttp/reference_sink template; debug/logging invalidates evidence
- **Budgets/variance**: provisional vs sre-approved; reproductionRunCount >= 2 for W-004 resolution
- **Tests**: JSON Schema validation via `com.networknt:json-schema-validator` (test-scoped)
- **Reference sink templates**: `reference-sink-deployment.yaml`, `reference-sink-service.yaml`

### What did NOT land

- No official reference execution (PR-9H-E2)
- No soak/RSS trend (PR-9H-F)
- W-004 remains **OPEN**
- W-011/W-012 unchanged

## PR-9H-E2L (Gentoo local Kubernetes reference lab)

### What landed

- **Scripts**: `scripts/local-reference-lab/` — inventory, kind setup, deploy, run scenarios, collect, cleanup (dry-run by default)
- **Config**: `config/local-reference-lab/` — kind cluster, namespace overlay, Prometheus/JFR/metrics overlays
- **Local sink**: `docker/local-reference-sink/` — test-only OTLP sink image
- **Local perf-app image**: `docker/perf-app/` + `preparePerfAppDockerContext`
- **Schema**: optional `labTier=LOCAL_REFERENCE_LAB` forces `w004Eligible=false` + `localEnvironment`
- **Evidence**: `evidence/reference-local/` (separate from official `evidence/reference/`)
- **Run (2026-06-14)**: S0/S1/S4 k6 at TARGET_RPS=20 — `20260614-local-1`

### PR-9H-E2L-METRICS (2026-06-14)

- **Diagnostics**: Prometheus **not installed** → no cAdvisor `container_*`; collector `otelcol_*` available at pod IP :8888; JFR **not configured**
- **Scripts**: `diagnose-local-metrics.sh`, `collect-local-collector-summary.sh`, `patch-local-jfr-startup.sh`, Prometheus/JFR docs
- **Evidence**: optional `collector-compact-summary.json` under `evidence/reference-local/.../20260614-local-metrics-diag/`
- W-004 remains **OPEN**; all metrics evidence non-authoritative

### PR-9H-E2L-FULL (2026-06-15, partial)

- **Prometheus**: kube-prometheus-stack on kind (`local-prom`)
- **S0/S1/S4**: k6 @ RPS=20 — `20260614-local-metrics-full-1` (JFR empty)

### PR-9H-E2L-JFR-fix (2026-06-15)

- **Fix:** `disk=true`, repository `/tmp/jfr/repository`, copy largest `.jfr` (no jcmd in JRE)
- **Run:** `20260614-local-jfr-fix-1` — JFR 1.9–2.7 MB/scenario; `jfrDetailedGcSummary` still missing
- **Status:** `COMPLETED_JFR_LOCAL_CAPTURE_AND_RERUN_NON_AUTHORITATIVE`
- W-004 **OPEN**

### Next steps

1. **PR-9H-E2-run** — official SRE/pre-prod REFERENCE

## PR-9H-E2 (first official REFERENCE run scaffolding)

### What landed

- **k6 ConfigMap**: `k6-scenarios-configmap.yaml` template + `prepareK6ReferenceConfigMap` Gradle task
- **Artifact hygiene**: root `.gitignore` rules for raw dumps under `evidence/reference/`
- **Evidence conventions**: `evidence/reference/README.md` directory layout
- **E2 docs**: `PR-9H-E2-preflight-checklist.md`, `PR-9H-E2-runbook.md`
- **Gradle**: `perfReferenceCreateSummarySkeleton`, enhanced `perfReferencePlan`/`perfReferenceValidateConfig`
- **Tests**: E2 doc presence, gitignore, k6 ConfigMap, skeleton schema validity

### What did NOT land

- No actual Kubernetes cluster execution (environment prerequisites pending)
- No committed REFERENCE evidence summaries
- W-004 remains **OPEN**
- W-011/W-012 unchanged

### Next steps

1. Complete E2 pre-flight checklist with SRE
2. **PR-9H-E2-run** — manual cluster execution (S0, S1, S4)
3. **PR-9H-E3** — second reproducibility run
4. **PR-9H-F** — soak/RSS trend
5. **PR-9H-G** — W-004 decision
