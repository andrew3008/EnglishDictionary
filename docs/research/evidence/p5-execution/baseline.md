# P5.0 — Baseline census

**Gate:** P5.0

**As of:** 2026-07-29

**Verdict:** `BASELINE_RECORDED`

**Machine-readable:** [baseline.json](./baseline.json)
**Production code changed:** no

## Repository

| Field | Value |
|---|---|
| HEAD | `84f448800db5851cc6ab9bbfc3f7a8e3d6d643e7` |
| Branch | `master` |
| Subject | P5 Part_2 Tests |
| Dirty | 2 files (`docs/analysis/plans/...plan.md`, `docs/otel-junit-extensions-plan-v2.md`) |
| Java | 21.0.6 |
| Gradle | 8.14 |
| Docker | `tcp://192.168.100.70:2375` → Server 28.0.4 linux (**AVAILABLE**) |

## Symbol corrections

| Plan name | Actual |
|---|---|
| `CompleteTracingPolicyManifestBuilder` | **does not exist** → `ConfigServerPolicyManifestBuilder` |

P5.1 already present: `P51CategoryALeafMissingTest` (plan marks COMPLETED).

## Resolved commands (corrections)

Mandatory Docker E2E:

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon
.\gradlew.bat :platform-tracing-e2e-tests:privateMBeanServerTest --no-daemon
```

Without `-PrunE2e` the aggregate suite is **SKIPPED** → treat as BLOCKED, not PASS.

M10 is **not** a Gradle task:

```powershell
.\platform-tracing-perf-tests\scripts\run-perf-scenario.ps1 -Scenario m10 -DockerHost tcp://192.168.100.70:2375
```

## Blockers recorded (not PASS)

1. **P5.7** — no committed exact JMH thresholds / benchmarks for Agent encode, Spring decode, applySnapshot outcomes, digest → risk `BLOCKED_BUDGET_MISSING`.
2. **P5.8** — M10/perf README still documents `/perf/admin` → `PlatformTracingControl`; must rewrite to private `applySnapshot` path before PASS.
3. **k6** — not on local PATH; confirm Docker-backed runner on Gentoo at execution time.
4. **M10 budget** `dynconf-no-stall` is `PENDING` / `evidence: null` in `docs/tracing/performance-budgets.yaml`.
