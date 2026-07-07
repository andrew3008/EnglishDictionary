# PR-0 baseline results — 2026-06-12 (final)

> Evidence-first report assembled by Cursor Composer 2.5 after re-check of repository artifacts.  
> Prior draft: [pr-0-baseline-results-2026-06-11.md](pr-0-baseline-results-2026-06-11.md)  
> Checklist: [pr-0-baseline-checklist.md](pr-0-baseline-checklist.md)  
> Scope: **PR-0 — Baseline lock: behavior, performance, dependency snapshot**

## PR-0 baseline status: PARTIAL

**Cannot mark COMPLETE.** Re-inspection on 2026-06-12 found **no new artifacts** from the missing baseline commands (E2E, JMH, `performanceReleaseGate`, fresh macro perf M10c/M10d, real Git SHA).

| Completion criterion | Status | Evidence |
|---------------------|--------|----------|
| Real Git SHA | **MISSING** | `git rev-parse HEAD` → not a git repository; metadata `gitSha: unknown` |
| `./gradlew pr0BaselineLock` PASS | **PASS** | BUILD SUCCESSFUL ~2026-06-12 (prior session) |
| Dependency snapshot PASS | **PASS** | 8 module `.txt` files + metadata JSON |
| Starter smoke PASS | **PASS** | `starter-dependency-smoke.txt` — PASSED |
| `RuntimeSamplingControlSmokeTest` recorded | **MISSING** | No `platform-tracing-e2e-tests/build/reports/tests/` |
| JMH baseline captured | **MISSING** | No `platform-tracing-bench/build/results/jmh/results.json`; no `baselines/` |
| `performanceReleaseGate` outcome recorded | **MISSING** | Task not run; no gate execution report |
| Macro perf recorded or waived | **PARTIAL** | M0/M5/M10 from documented official session `2026-06-10_official`; M10c/M10d not found; no fresh 2026-06-12 runs |

---

## Environment

| Field | Value |
|-------|-------|
| **Git commit** | Requires manual review — `git rev-parse HEAD` failed: not a git repository |
| **Git short SHA** | `unknown` (`dependency-snapshot-metadata.json`, `starter-dependency-smoke.txt`) |
| **Date** | 2026-06-12 (final report assembly) |
| **Operator** | Cursor Composer 2.5 (evidence re-check) |
| **Machine / environment** | `DESKTOP-OEGBG7F` (local Windows dev host) |
| **Java version** | 21.0.6 (Oracle JDK, build 21.0.6+8-LTS-188) |
| **Gradle version** | 8.14 (revision 34c560e3be961658a6fbcd7170ec2443a228b109) |
| **OS** | Windows 10 (10.0.19045.0) |
| **CPU** | 13th Gen Intel(R) Core(TM) i5-13500, 20 logical cores |
| **Memory** | ~63.7 GB (68448153600 bytes) |
| **Docker available** | **Reference lab (Gentoo): yes** — `tcp://192.168.100.70:2375` (operator confirmed; perf `summary.json` evidence). **Local Windows host:** E2E not executed; no E2E test reports in workspace. |
| **Reference perf lab used** | yes — official session `docs/tracing/perf-results/2026-06-10_official/` (Gentoo 192.168.100.70, 8 vCPU, 32 GB RAM) |

---

## Unit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test` | NOT RUN | No standalone `./gradlew test` log. Subproject `:test` ran inside `pr0BaselineLockAutomated`. |
| `./gradlew check` | PASS | Via `pr0BaselineLockAutomated` BUILD SUCCESSFUL (~2026-06-12). |

**Module test reports (0 failures):**

| Module | Tests | Failures | Report |
|--------|------:|---------:|--------|
| `platform-tracing-api` | 71 | 0 | `platform-tracing-api/build/reports/tests/test/index.html` |
| `platform-tracing-core` | 63 | 0 | `platform-tracing-core/build/reports/tests/test/index.html` |
| `platform-tracing-otel-extension` | 388 | 0 | `platform-tracing-otel-extension/build/reports/tests/test/index.html` |
| `platform-tracing-spring-boot-autoconfigure` | 150 | 0 | `platform-tracing-spring-boot-autoconfigure/build/reports/tests/test/index.html` |
| `platform-tracing-autoconfigure-webmvc` | 29 | 0 | `platform-tracing-autoconfigure-webmvc/build/reports/tests/test/index.html` |
| `platform-tracing-autoconfigure-webflux` | 27 | 0 | `platform-tracing-autoconfigure-webflux/build/reports/tests/test/index.html` |
| `platform-tracing-test` | 41 | 0 | `platform-tracing-test/build/reports/tests/test/index.html` |
| `platform-tracing-bench` | 25 | 0 | `platform-tracing-bench/build/reports/tests/test/index.html` (2 ignored) |
| `platform-tracing-collector-config` | 12 | 0 | `platform-tracing-collector-config/build/reports/tests/test/index.html` |
| `platform-tracing-e2e-tests` | — | — | **No** `build/reports/tests/` — `:test` SKIPPED without `-PrunE2e` |

**Failed tests:** none in unit-test reports above.

---

## ArchUnit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test --tests "*Arch*Test"` | NOT RUN | No dedicated Arch-only run. |

**Covered by subproject `:test` — 13 Arch*Test class reports, 0 failures** (inferred PASS; requires manual confirmation for audit trail).

Modules: `platform-tracing-test`, `platform-tracing-core`, `platform-tracing-otel-extension`, `platform-tracing-spring-boot-autoconfigure`, `platform-tracing-autoconfigure-webmvc`.

---

## E2E tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-e2e-tests:test -PrunE2e` | NOT RUN | No `platform-tracing-e2e-tests/build/reports/tests/` directory. |
| `RuntimeSamplingControlSmokeTest` | NOT RUN | No E2E test report evidence. |

**E2E count:** — / 42 (not verified)

**Artifact path:** not present.

---

## JMH baseline result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-bench:jmh` | NOT RUN | `platform-tracing-bench/build/results/jmh/results.json` — **not found** |
| `./gradlew :platform-tracing-bench:jmhSaveBaseline` | NOT RUN | `platform-tracing-bench/baselines/` — **not found** |

**Hardware profile ID:** — (not captured)

**Baseline paths:**

```text
platform-tracing-bench/baselines/<profileId>/results.json          — not found
platform-tracing-bench/baselines/<profileId>/baseline-metadata.json  — not found
platform-tracing-bench/build/results/jmh/results.json                — not found
```

**Hot-path benchmarks (source only; JMH not executed):**

- [ ] `CompositeSamplerBenchmark`
- [ ] `CompositeSamplerPolicyBranchesBenchmark`
- [ ] `CompositeSamplerConcurrentUpdateBenchmark`
- [ ] `ScrubbingEngineBenchmark`
- [ ] `ScrubbingPerRuleBenchmark`
- [ ] `QueueOfferBenchmark`
- [ ] `CompositePipelineBenchmark`

---

## PerformanceReleaseGateTest result

| Command | Result | Expected for PR-0 |
|---------|--------|-------------------|
| `./gradlew :platform-tracing-bench:performanceReleaseGate` | NOT RUN | FAIL acceptable if hard budgets `PENDING` |
| `./gradlew :platform-tracing-bench:test --tests "*PerformanceBudgetsContractTest*"` | PASS (via `:test`) | 10 tests, 0 failures |

**Bench test report evidence:**

- `PerformanceBudgetsContractTest` — 10 tests, 0 failures
- `PerformanceReleaseGateTest` — 2 tests, **2 ignored** (requires `performanceReleaseGate` task / `-Dplatform.release.gate=true`)

**Gate task outcome:** NOT RUN — operator must execute and record PASS/FAIL explicitly.

---

## Macro perf results

**Documented official baseline** (not a fresh 2026-06-12 re-run): `docs/tracing/perf-results/2026-06-10_official/`  
**Aggregate:** `session-summary.json`, `architecture-committee-review.md`

### M0 — host calibration

| Run | Path | Result | Notes |
|-----|------|--------|-------|
| M0 run 1 | `2026-06-10_official/2026-06-10_234306/m0/` | VALID | `runValid: true` |
| M0 run 2 | `2026-06-10_official/2026-06-10_235521/m0/` | VALID | Session baseline; CPU 49.41 s, RSS 509.7 MB avg |

Session: 6 M0 runs, 4 valid; calibration noise **1.77%** CPU (borderline vs 1.0% threshold).

### M5 — agent + extension + export delta

| Field | Value |
|-------|-------|
| **Path** | `2026-06-10_official/2026-06-11_012235/m5/` |
| **Result** | VALID |
| **Δ CPU vs M0** | +49.76% (run); median **+48.14%** (session) |
| **Δ RSS vs M0** | +24.76% (run); median **+25.37%** (session) |
| **Documented baseline FAIL** | **yes** — vs 3% CPU / 10% RSS budgets |

### M10 / M10c / M10d — config reload / runtime mutation

| Scenario | Path | Result | Notes |
|----------|------|--------|-------|
| M10 | `2026-06-10_official/2026-06-11_044529/m10/` | VALID | Δ CPU +44.95%, Δ RSS +19.6% vs M0 |
| M10c | — | SKIPPED — evidence not found | No artifacts under `perf-results/` |
| M10d | — | SKIPPED — evidence not found | No artifacts under `perf-results/` |

**Fresh macro perf runs (2026-06-12):** NOT RUN — no new `docs/tracing/perf-results/2026-06-12*/` artifacts.

---

## Dependency snapshot location

| Item | Path |
|------|------|
| Snapshot directory | `docs/architecture/baselines/pr-0/dependency-snapshot/` |
| Metadata | `docs/architecture/baselines/pr-0/dependency-snapshot-metadata.json` |
| Gradle task | `./gradlew pr0DependencySnapshot` |
| Date captured | 2026-06-12T14:19:49+03:00 |

**Modules snapshotted (8 files):** api, core, otel-extension, spring-boot-autoconfigure, autoconfigure-webmvc, autoconfigure-webflux, starter-servlet, starter-reactive.

**Result:** PASS

---

## Starter dependency smoke

| Command | Result |
|---------|--------|
| `./gradlew pr0StarterDependencySmoke` | PASS |

**Report:** `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt` — PASSED (2026-06-12 14:21:23 MSK)

**Expectations confirmed:** servlet/reactive starters, no otel-extension on classpath, stack isolation — all verified.

---

## Automated PR-0 gate

| Command | Result |
|---------|--------|
| `./gradlew pr0BaselineLockAutomated` | PASS — BUILD SUCCESSFUL (~2026-06-12) |
| `./gradlew pr0BaselineLock` | PASS — BUILD SUCCESSFUL (~2026-06-12) |

**Caveat:** `validateCollectorConfigs` may skip Docker failure without `-PstrictValidation` on local Windows host.

---

## Known failures

Expected baseline state — not PR-0 regressions:

```text
- M5 macro perf FAIL vs CPU/RSS budget (official session 2026-06-10_official):
  median Δ CPU +48.14%, Δ RSS +25.37% vs M0 (budgets 3% / 10%).
- performanceReleaseGate: task NOT RUN; hard budgets PENDING in performance-budgets.yaml
  (expected FAIL when task executed — architecture-committee-review.md §1).
- M0 host calibration borderline: 1.77% CPU drift vs 1.0% threshold.
```

---

## Known risks

```text
- Actuator MUTATION exposed in prod without guard — migration PR-11.
- platform-tracing-core has api opentelemetry-api — MIGRATION_RISK.
- TracingConfigReconciler not found in current codebase — target-only.
- Git SHA not pinned — baseline commit unknown in snapshot metadata.
```

---

## Manual review required

```text
- Git: workspace is not a git checkout; re-run all baseline steps from tagged commit and
  update dependency-snapshot-metadata.json with real SHA.
- E2E: RuntimeSamplingControlSmokeTest not executed — blocking COMPLETE status.
- JMH: jmh + jmhSaveBaseline not executed — blocking COMPLETE status.
- performanceReleaseGate: task not executed — blocking COMPLETE status (record FAIL if PENDING budgets).
- M10c/M10d: no perf artifacts — run or explicitly waive for PR-0 sign-off.
- Macro perf: only documented 2026-06-10_official session used; no fresh 2026-06-12 re-run.
```

---

## Next commands to run

Execute from git checkout with real SHA, then update this report:

```powershell
# Pin Git SHA in snapshot metadata
git rev-parse HEAD
./gradlew pr0DependencySnapshot
./gradlew pr0StarterDependencySmoke

# E2E (Gentoo Docker)
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*RuntimeSamplingControlSmokeTest*"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e

# JMH baseline
./gradlew :platform-tracing-bench:jmh
./gradlew :platform-tracing-bench:jmhSaveBaseline

# Release gate (record outcome even if expected FAIL)
./gradlew :platform-tracing-bench:performanceReleaseGate

# Macro perf (reference lab)
cd platform-tracing-perf-tests
.\scripts\run-perf-scenario.ps1 -Scenario m10c -DockerHost tcp://192.168.100.70:2375
.\scripts\run-perf-scenario.ps1 -Scenario m10d -DockerHost tcp://192.168.100.70:2375

# Full automated gate
./gradlew pr0BaselineLock
```

After all artifacts exist: set **PR-0 baseline status: COMPLETE** only when every row in the completion criteria table is satisfied.

---

## Sign-off

| Role | Name | Date | PR-0 baseline accepted |
|------|------|------|------------------------|
| Operator | | | ☐ |
| Platform engineer | | | ☐ |
| SRE (perf/e2e) | | | ☐ |

---

*PR-0 baseline status: PARTIAL (2026-06-12). Does not approve production rollout or migration. PR-1 must not start until COMPLETE or explicit waiver.*
