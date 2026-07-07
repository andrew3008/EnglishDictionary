# platform-tracing-perf-harness

Macro/load/RSS evidence harness: **hybrid two-tier** model for W-004 pre-prod performance evidence.

| Tier | Purpose | Authoritative for W-004? |
|------|---------|--------------------------|
| **SMOKE** | Local/dev functional check; short k6 | **No** |
| **REFERENCE** | Kubernetes/pre-prod; open-model load; JFR; soak | **Yes** (PR-9H-E2+ execution) |

JMH evidence (W-003 RESOLVED) does **not** close W-004.

## Quick start (SMOKE tier)

```text
# Terminal 1
./gradlew :platform-tracing-perf-harness:bootRun -PperfProfiles=smoke

# Terminal 2 — local k6
./gradlew :platform-tracing-perf-harness:perfSmoke

# Terminal 2 — Docker k6 (no local k6 required)
./gradlew :platform-tracing-perf-harness:perfSmoke -PperfSmokeUseDocker=true
```

Remote Docker lab (Gentoo `192.168.100.70:2375`):

```text
./gradlew :platform-tracing-perf-harness:perfSmoke -PperfSmokeUseDocker=true \
  -PDockerHost=tcp://192.168.100.70:2375 \
  -PperfBaseUrl=http://<host-from-gentoo>:8080
```

Or: `scripts/run-perf-smoke.ps1 -DockerHost tcp://192.168.100.70:2375 -BaseUrl http://...`

If k6 and Docker are unavailable, `perfSmoke` **skips cleanly** (normal `build` unaffected).

## REFERENCE tier (PR-9H-E2 — manual cluster execution)

Kubernetes templates: `config/k8s/`. Runbook: `evidence/templates/PR-9H-E2-runbook.md`.

```text
./gradlew :platform-tracing-perf-harness:perfReferenceValidateConfig
./gradlew :platform-tracing-perf-harness:perfReferencePlan -PperfProfileId=k8s-reference-lab-v1
./gradlew :platform-tracing-perf-harness:prepareK6ReferenceConfigMap
./gradlew :platform-tracing-perf-harness:perfReferenceCreateSummarySkeleton \
  -PperfProfileId=k8s-reference-lab-v1 -PperfScenario=S4 -PperfRunId=<runId> \
  -PperfNamespace=<ns> -PperfTargetRps=<rps> \
  -PperfOutputDir=build/perf-results/reference/k8s-reference-lab-v1/<runId>
```

Cluster execution is **manual** per runbook. `perfReferenceRun` validates properties only — never fabricates metrics.

E2 defaults: `w004Eligible=false`, `nonAuthoritative=true`, reasons include `singleRunOnly`, `provisionalBudgetOnly`.

### Key methodology (REFERENCE)

| Domain | Primary metric / rule |
|--------|----------------------|
| CPU | `rate(container_cpu_usage_seconds_total)` → `cpu.per1kRps`; throttleRatioPct <= 5% |
| Memory | `container_memory_working_set_bytes/cAdvisor` |
| Load | constant-arrival-rate; achievedRpsPct >= 0.95; dropped_iterations = 0 |
| Latency | k6/http_req_duration steady window p95/p99 |
| JFR | `-XX:StartFlightRecording` at startup; symmetric S0/test |
| Collector | otlphttp/reference_sink; `backpressureEvidenceValid=true` |
| Budgets | provisional until SRE-approved; W-004 needs sre-approved |

Evidence contract:

- `evidence/templates/reference-summary.schema.json`
- `evidence/templates/W-004-resolution-checklist.md`
- `evidence/templates/PR-9H-reference-run-report-template.md`
- `config/profiles/reference-scenario-matrix.md`

Committed reference summaries (future): `evidence/reference/<profileId>/` — compact JSON + markdown only.

## Gradle properties (REFERENCE)

| Property | Default | Purpose |
|----------|---------|---------|
| `perfProfiles` | `smoke` | Spring profile for bootRun / smoke tag |
| `perfBaseUrl` | `http://localhost:8080` | SUT URL (from k6 runner perspective) |
| `perfSmokeUseDocker` | auto if no k6 | Force Docker k6 |
| `perfSmokeDuration` | `30s` | k6 duration |
| `perfSmokeTargetRps` | `5` | k6 arrival rate |
| `DockerHost` | — | Remote daemon e.g. `tcp://192.168.100.70:2375` |
| `perfSmokeOutputDir` | auto timestamp | Artifact directory |

| Property | Purpose |
|----------|---------|
| `perfReferenceConfirmed` | Must be `true` to invoke `perfReferenceRun` |
| `perfProfileId` | Reference lab profile id |
| `perfNamespace` | Kubernetes namespace |
| `perfScenario` | S0–S6 |
| `perfOutputDir` | Must be under `build/perf-results/reference/` |

## Artifacts (gitignored)

`build/perf-results/smoke/<timestamp>/`:

| File | Content |
|------|---------|
| `summary.json` | Tier-stamped metadata (`evidenceTier: SMOKE`, `w004Eligible: false`) |
| `k6-summary.json` | k6 summary export |
| `command.txt` | Reproducibility |

Example schema: `evidence/smoke-examples/sample-summary.json` (non-authoritative).

## Scenario profiles (S0–S6)

See [config/profiles/README.md](config/profiles/README.md).

## Perf app endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /perf/health` | Smoke readiness |
| `GET /perf/fast` | Fast traced path |
| `GET /perf/work` | Deterministic CPU work |
| `GET /perf/validation/valid` | Lenient valid span path |
| `GET /perf/validation/missing` | Missing required attrs |
| `/actuator/prometheus` | Metrics scrape (REFERENCE tier) |

## Gradle tasks

| Task | Role |
|------|------|
| `bootRun` | Start perf app |
| `test` | MockMvc + schema contract tests |
| `perfSmoke` | k6 functional run (local or Docker) |
| `perfReferenceRun` | Refuses until PR-9H-E2 (validates props) |
| `perfReferencePlan` | Print reference run plan |
| `perfReferenceValidateConfig` | Validate K8s templates + schemas |
| `perfPrintCommands` | Print documented commands |
| `prepareK6SmokeDockerContext` | Docker build context (COPY scenarios) |

## Warning Register

- **W-004**: OPEN — smoke is non-authoritative
- **W-011 / W-012**: DIAGNOSTIC — no macro classification from smoke
- **W-003**: RESOLVED (JMH only)

## Related docs

- [docs/perf-evidence/PR-9H-macro-load-rss-harness.md](../docs/perf-evidence/PR-9H-macro-load-rss-harness.md)
- [docs/perf-evidence/PR-9H-macro-harness-architecture-decision.md](../docs/perf-evidence/PR-9H-macro-harness-architecture-decision.md)

## Anti-patterns

- Do not treat SMOKE latency as production p99
- Do not close W-004 from local smoke
- Do not commit `build/perf-results/` or place smoke under `evidence/reference/`
- Do not run soak in normal CI
