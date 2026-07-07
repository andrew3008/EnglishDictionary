# PR-0 baseline results — 2026-06-11

> Filled from repository evidence by Cursor Composer 2.5.  
> Checklist: [pr-0-baseline-checklist.md](pr-0-baseline-checklist.md)  
> Scope: **PR-0 — Baseline lock: behavior, performance, dependency snapshot**

**PR-0 status:** **Partial baseline captured.** Automated Gradle gate, dependency snapshot, and starter smoke are recorded. JMH baseline, E2E, standalone `./gradlew test`, and `performanceReleaseGate` task were **not run** in this workspace (or have no artifact evidence). Macro perf uses **documented official session** `2026-06-10_official` (reference lab), not a fresh local run.

---

## Environment

| Field | Value |
|-------|-------|
| **Git commit** | Requires manual review — `git rev-parse HEAD` failed: not a git repository |
| **Git short SHA** | `unknown` (also recorded in `dependency-snapshot-metadata.json` and `starter-dependency-smoke.txt`) |
| **Date** | 2026-06-11 (report assembly); snapshot/smoke artifacts timestamped 2026-06-12T14:19–14:21+03:00 |
| **Operator** | Cursor Composer 2.5 (evidence-first report) |
| **Machine / environment** | `DESKTOP-OEGBG7F` (local Windows dev host) |
| **Java version** | 21.0.6 (Oracle JDK, build 21.0.6+8-LTS-188) |
| **Gradle version** | 8.14 (revision 34c560e3be961658a6fbcd7170ec2443a228b109) |
| **OS** | Windows 10 (10.0.19045.0) |
| **CPU** | 13th Gen Intel(R) Core(TM) i5-13500, 20 logical cores (`dependency-snapshot-metadata.json`: 20) |
| **Memory** | ~63.7 GB (68448153600 bytes, `Win32_ComputerSystem.TotalPhysicalMemory`) |
| **Docker available** | **Reference lab (Gentoo): yes** — `tcp://192.168.100.70:2375` (operator confirmed; evidence in official perf `summary.json` files). **Local Windows host (`DESKTOP-OEGBG7F`): Requires manual review** — `validateCollectorConfigs` during `pr0BaselineLockAutomated` logged connect timeout to `192.168.100.70:2375` from this machine; E2E not executed locally. |
| **Reference perf lab used** | yes — documented official session on Gentoo `192.168.100.70` (8 vCPU, 32 GB RAM); artifacts under `docs/tracing/perf-results/2026-06-10_official/` |

---

## Unit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test` | NOT RUN | No standalone `./gradlew test` log in workspace. Subproject `:test` tasks ran as part of `pr0BaselineLockAutomated` (see module reports below). |
| `./gradlew check` | PASS | Evidence: `./gradlew pr0BaselineLockAutomated` → **BUILD SUCCESSFUL** (~2026-06-12). Equivalent to all subproject `:check` tasks + `verifyOtelBomAlignment`. |

**Module test reports (0 failures where present):**

| Module | Tests | Failures | Report path |
|--------|------:|---------:|-------------|
| `platform-tracing-api` | 71 | 0 | `platform-tracing-api/build/reports/tests/test/index.html` |
| `platform-tracing-core` | 63 | 0 | `platform-tracing-core/build/reports/tests/test/index.html` |
| `platform-tracing-otel-extension` | 388 | 0 | `platform-tracing-otel-extension/build/reports/tests/test/index.html` |
| `platform-tracing-spring-boot-autoconfigure` | 150 | 0 | `platform-tracing-spring-boot-autoconfigure/build/reports/tests/test/index.html` |
| `platform-tracing-autoconfigure-webmvc` | 29 | 0 | `platform-tracing-autoconfigure-webmvc/build/reports/tests/test/index.html` |
| `platform-tracing-autoconfigure-webflux` | 27 | 0 | `platform-tracing-autoconfigure-webflux/build/reports/tests/test/index.html` |
| `platform-tracing-test` | 41 | 0 | `platform-tracing-test/build/reports/tests/test/index.html` |
| `platform-tracing-bench` | 25 | 0 | `platform-tracing-bench/build/reports/tests/test/index.html` (2 ignored) |
| `platform-tracing-collector-config` | 12 | 0 | `platform-tracing-collector-config/build/reports/tests/test/index.html` |
| `platform-tracing-e2e-tests` | — | — | **No** `build/reports/tests/` — `:test` **SKIPPED** without `-PrunE2e` |

**Failed tests (if any):**

```text
(none in module unit-test reports above)
```

---

## ArchUnit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test --tests "*Arch*Test"` | NOT RUN | No dedicated Arch-only run log. |

**Covered by subproject `:test` reports — 13 Arch*Test class reports found, 0 failures:**

```text
platform-tracing-test
  TracingArchRulesTest, OtelSdkArchRulesTest, OtelDirectIntegrationRulesTest, EscapeHatchArchRuleTest
platform-tracing-core
  OtelDirectIntegrationArchTest
platform-tracing-otel-extension
  ExtensionNoSpringDependencyArchTest, SafeBoundaryArchTest, SpiApiCompatibilityArchTest,
  ResourceKeysNotInSpanProcessorsArchTest, OtelDirectIntegrationArchTest
platform-tracing-spring-boot-autoconfigure
  OtelDirectIntegrationArchTest, KafkaOutboundNoSpanArchTest
platform-tracing-autoconfigure-webmvc
  ServletOutboundNoSpanArchTest
```

**ArchUnit outcome:** PASS (inferred from parent module reports with 0 failures) — requires manual confirmation if Arch-only re-run is needed.

---

## E2E tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-e2e-tests:test -PrunE2e` | NOT RUN | No `platform-tracing-e2e-tests/build/reports/tests/` directory. Gradle output from `pr0BaselineLockAutomated`: `:platform-tracing-e2e-tests:test SKIPPED`. Docker on Gentoo (`192.168.100.70:2375`) is available for operator-run E2E; not executed in this workspace. |
| `RuntimeSamplingControlSmokeTest` | NOT RUN | No E2E test report evidence in workspace. |

**E2E count (if full run):** — / 42 tests (expected per checklist; not verified)

**Artifact / log path:**

```text
(not present — operator must run with -PrunE2e; use Gentoo Docker:
  $env:DOCKER_HOST = "tcp://192.168.100.70:2375"
  ./gradlew :platform-tracing-e2e-tests:test -PrunE2e)
```

---

## JMH baseline result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-bench:jmh` | NOT RUN | `platform-tracing-bench/build/results/jmh/results.json` — **not found** |
| `./gradlew :platform-tracing-bench:jmhSaveBaseline` | NOT RUN | `platform-tracing-bench/baselines/` — **empty / not found** |

**Hardware profile ID:** — (no baseline captured on `DESKTOP-OEGBG7F`)

**Baseline paths:**

```text
platform-tracing-bench/baselines/<profileId>/results.json          — not found
platform-tracing-bench/baselines/<profileId>/baseline-metadata.json  — not found
platform-tracing-bench/build/results/jmh/results.json                — not found
```

**Hot-path benchmarks (source present; JMH run not executed):**

- [ ] `CompositeSamplerBenchmark` — source exists
- [ ] `CompositeSamplerPolicyBranchesBenchmark` — source exists
- [ ] `CompositeSamplerConcurrentUpdateBenchmark` — source exists
- [ ] `ScrubbingEngineBenchmark` — source exists
- [ ] `ScrubbingPerRuleBenchmark` — source exists
- [ ] `QueueOfferBenchmark` — source exists
- [ ] `CompositePipelineBenchmark` — source exists

---

## PerformanceReleaseGateTest result

| Command | Result | Expected for PR-0 |
|---------|--------|-------------------|
| `./gradlew :platform-tracing-bench:performanceReleaseGate` | NOT RUN | FAIL acceptable if hard budgets `PENDING` |
| `./gradlew :platform-tracing-bench:test --tests "*PerformanceBudgetsContractTest*"` | PASS (via `:test`) | Contract tests run in normal bench `:test` |

**Evidence from `platform-tracing-bench/build/reports/tests/test/`:**

- `PerformanceBudgetsContractTest` — **10 tests, 0 failures**
- `PerformanceReleaseGateTest` — **2 tests, 2 ignored** (disabled unless `-Dplatform.release.gate=true`, i.e. `performanceReleaseGate` task)

**Notes:**

```text
performanceReleaseGate task not executed in this workspace.
Normal test lifecycle skips PerformanceReleaseGateTest (ignored).
Documented pre-release state: hard budgets PENDING in performance-budgets.yaml
(see docs/tracing/perf-results/2026-06-10_official/architecture-committee-review.md §1).
```

---

## Macro perf results

**Source:** documented official session `docs/tracing/perf-results/2026-06-10_official/` (reference lab Gentoo, not local Windows host).  
**Aggregate:** `session-summary.json`, committee review: `architecture-committee-review.md`.

### M0 — host calibration

| Run | Path | Result | Notes |
|-----|------|--------|-------|
| M0 run 1 | `docs/tracing/perf-results/2026-06-10_official/2026-06-10_234306/m0/` | VALID | `runValid: true` in `summary.json` |
| M0 run 2 | `docs/tracing/perf-results/2026-06-10_official/2026-06-10_235521/m0/` | VALID | **Session baseline** per committee review; CPU 49.41 s, RSS avg 509.7 MB |

Session aggregate: 6 M0 runs, 4 valid; M0/M0 calibration noise **1.77%** CPU drift (committee: borderline vs 1.0% threshold).

### M5 — agent + extension + export delta

| Field | Value |
|-------|-------|
| **Path** | `docs/tracing/perf-results/2026-06-10_official/2026-06-11_012235/m5/` |
| **Result** | VALID (`runValid: true`, k6 exit 0) |
| **Δ CPU vs M0** | **+49.76%** (single run); session median **+48.14%** (`session-summary.json`) |
| **Δ RSS vs M0** | **+24.76%** (single run); session median **+25.37%** |
| **Documented baseline FAIL** | **yes** — vs budgets 3% CPU / 10% RSS; committee documents **+48.1% CPU**, **+25.4% RSS** |

### M10 / M10c / M10d — config reload / runtime mutation

| Scenario | Path | Result | Notes |
|----------|------|--------|-------|
| M10 | `docs/tracing/perf-results/2026-06-10_official/2026-06-11_044529/m10/` | VALID | Δ CPU +44.95%, Δ RSS +19.6% vs M0 baseline |
| M10c | — | SKIPPED — evidence not found | No `m10c` entries under `2026-06-10_official/` |
| M10d | — | SKIPPED — evidence not found | No `m10d` entries under `2026-06-10_official/` |

---

## Dependency snapshot location

| Item | Path |
|------|------|
| Snapshot directory | `docs/architecture/baselines/pr-0/dependency-snapshot/` |
| Metadata | `docs/architecture/baselines/pr-0/dependency-snapshot-metadata.json` |
| Gradle task | `./gradlew pr0DependencySnapshot` |
| Date captured | 2026-06-12T14:19:49+03:00 (`savedAt` in metadata) |

**Modules snapshotted (8 `.txt` files present):**

```text
platform-tracing-api
platform-tracing-core
platform-tracing-otel-extension
platform-tracing-spring-boot-autoconfigure
platform-tracing-autoconfigure-webmvc
platform-tracing-autoconfigure-webflux
platform-tracing-spring-boot-starter-servlet
platform-tracing-spring-boot-starter-reactive
```

**Snapshot result:** PASS (artifacts present; produced by `pr0DependencySnapshot` during `pr0BaselineLockAutomated`)

---

## Starter dependency smoke

| Command | Result |
|---------|--------|
| `./gradlew pr0StarterDependencySmoke` | PASS |

**Report path:** `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt`  
**Report content:** `PR-0 starter dependency smoke — PASSED` (2026-06-12 14:21:23 MSK)

**Expectations confirmed:**

- [x] Servlet apps → `platform-tracing-spring-boot-starter-servlet` only (+ BOM)
- [x] Reactive apps → `platform-tracing-spring-boot-starter-reactive` only (+ BOM)
- [x] No direct app dependency on `otel-extension` via starter classpath
- [x] Stack isolation: servlet starter without webflux; reactive without webmvc

---

## Automated PR-0 gate

| Command | Result |
|---------|--------|
| `./gradlew pr0BaselineLockAutomated` | PASS — BUILD SUCCESSFUL (~2026-06-12, ~1m 25s) |
| `./gradlew pr0BaselineLock` | PASS — BUILD SUCCESSFUL (~2026-06-12, includes manual-step reminders) |

**Caveat:** `validateCollectorConfigs` logged Docker connect timeout to `192.168.100.70:2375` from local Windows host; task ignores failure without `-PstrictValidation`. Docker on Gentoo reference lab is available (operator confirmed); perf session artifacts used that endpoint successfully. Subproject unit tests passed from cache/reports.

---

## Known failures

Document **expected** failures — do not treat as PR-0 regressions:

```text
- M5 macro perf FAIL vs CPU/RSS budget (documented official session 2026-06-10_official):
  median Δ CPU +48.14%, Δ RSS +25.37% vs M0; budgets 3% / 10%.
  Example run: 2026-06-11_012235/m5/summary.json → Δ CPU +49.76%, Δ RSS +24.76%.
- performanceReleaseGate / PerformanceReleaseGateTest: expected FAIL/skipped while hard budgets
  remain PENDING in performance-budgets.yaml (architecture-committee-review.md §1).
- M0 host calibration borderline: 1.77% CPU drift vs 1.0% qualification threshold (official session).
```

---

## Known risks

```text
- Actuator MUTATION exposed in prod without guard — migration PR-11 (inventory / migration plan).
- platform-tracing-core has api opentelemetry-api — MIGRATION_RISK (inventory).
- TracingConfigReconciler not found in current codebase — target-only (inventory / migration plan).
- Git SHA unavailable in this workspace — baseline commit not pinned in snapshot metadata.
```

---

## Manual review required

```text
- Git commit: repository is not a git checkout on DESKTOP-OEGBG7F; operator must re-run snapshot
  from a tagged commit and update metadata with real SHA.
- E2E full run not found; operator must run (Gentoo Docker at `tcp://192.168.100.70:2375`):
  $env:DOCKER_HOST = "tcp://192.168.100.70:2375"
  ./gradlew :platform-tracing-e2e-tests:test -PrunE2e
  Minimum: --tests "*RuntimeSamplingControlSmokeTest*"
- JMH baseline not captured on this machine; operator must run jmh + jmhSaveBaseline.
- performanceReleaseGate task not run; operator must run and record PASS/FAIL explicitly.
- M10c/M10d perf artifacts not found in official session folder; run if required for PR-0 sign-off.
- Local Windows host may need `DOCKER_HOST=tcp://192.168.100.70:2375` for E2E/collector validation (Gentoo Docker confirmed available).
- ArchUnit: inferred PASS from module reports; optional dedicated re-run for audit trail.
- PR-0 baseline is NOT fully complete until JMH, E2E, and optional fresh perf runs are recorded.
```

---

## Next commands to run

From [pr-0-baseline-checklist.md](pr-0-baseline-checklist.md) — not yet executed or missing artifacts:

```powershell
# Standalone test gate (optional audit; covered partially by pr0BaselineLockAutomated)
./gradlew test

# E2E (Gentoo Docker — set DOCKER_HOST on Windows if needed)
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*RuntimeSamplingControlSmokeTest*"

# JMH baseline (this machine or reference lab)
./gradlew :platform-tracing-bench:jmh
./gradlew :platform-tracing-bench:jmhSaveBaseline

# Release gate (record outcome even if expected FAIL)
./gradlew :platform-tracing-bench:performanceReleaseGate

# Macro perf (reference lab — if M10c/M10d needed)
cd platform-tracing-perf-tests
.\scripts\run-perf-scenario.ps1 -Scenario m0
.\scripts\run-perf-scenario.ps1 -Scenario m0
.\scripts\run-perf-scenario.ps1 -Scenario m5 -DockerHost tcp://192.168.100.70:2375
.\scripts\run-perf-scenario.ps1 -Scenario m10c -DockerHost tcp://192.168.100.70:2375
.\scripts\run-perf-scenario.ps1 -Scenario m10d -DockerHost tcp://192.168.100.70:2375

# Re-pin dependency snapshot from git checkout
./gradlew pr0DependencySnapshot
./gradlew pr0StarterDependencySmoke
```

After completing runs: update this file or create `pr-0-baseline-results-<date>-final.md` with new evidence paths.

---

## Sign-off

| Role | Name | Date | PR-0 baseline accepted |
|------|------|------|------------------------|
| Operator | | | ☐ |
| Platform engineer | | | ☐ |
| SRE (perf/e2e) | | | ☐ |

---

*PR-0 partial baseline recorded 2026-06-11 (last updated from repository evidence). Does not approve production rollout, migration, or architecture changes. PR-1 must not start until open items above are resolved or explicitly waived.*
