# PR-0 — Baseline checklist

Reproducible steps to capture migration baseline **before PR-1**.

**Rules:** no production code changes during this checklist; record outcomes in [pr-0-baseline-results-template.md](pr-0-baseline-results-template.md).

---

## 0. Environment capture

Record in results template:

```powershell
git rev-parse HEAD
git rev-parse --short HEAD
java -version
./gradlew --version
[System.Environment]::OSVersion
$env:COMPUTERNAME
```

For JMH/perf: use **same hardware profile** for before/after comparisons (see `platform-tracing-bench/baselines/<profileId>/`).

---

## 1. Automated PR-0 gate (recommended first)

From repository root:

```powershell
./gradlew pr0BaselineLock
```

Automated only (no manual steps):

```powershell
./gradlew pr0BaselineLockAutomated
```

Equivalent manual breakdown:

```powershell
./gradlew check
./gradlew pr0DependencySnapshot
./gradlew pr0StarterDependencySmoke
```

**Expected:**

- `check` — all subproject tests + `verifyOtelBomAlignment` green;
- `pr0DependencySnapshot` — writes `docs/architecture/baselines/pr-0/dependency-snapshot/*.txt`;
- `pr0StarterDependencySmoke` — PASS, writes `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt`.

---

## 2. Unit tests (all modules)

```powershell
./gradlew test
```

**Scope:** all subprojects with `test` task (213 test classes per inventory).

**Notes:**

- E2E module tests are **skipped** unless `-PrunE2e` (see section 5).
- `platform-tracing-perf-tests` has no unit test source set.

---

## 3. Full verification gate

```powershell
./gradlew check
```

Includes per-module `test`, `verifyOtelBomAlignment`, `verifyAgentJarContents` / `verifyExtensionSpiRegistration` (otel-extension), contract tests in bench module, etc.

---

## 4. ArchUnit tests

ArchUnit tests run as part of `./gradlew test`. Targeted re-run:

```powershell
./gradlew test --tests "*Arch*Test"
```

**Modules with ArchUnit / architecture tests (inventory):**

| Module | Example test classes |
|--------|---------------------|
| `platform-tracing-test` | `TracingArchRulesTest`, `OtelSdkArchRulesTest`, `EscapeHatchArchRuleTest` |
| `platform-tracing-core` | `OtelDirectIntegrationArchTest` |
| `platform-tracing-otel-extension` | `ExtensionNoSpringDependencyArchTest`, `SafeBoundaryArchTest`, `SpiApiCompatibilityArchTest`, `ResourceKeysNotInSpanProcessorsArchTest`, `OtelDirectIntegrationArchTest` |
| `platform-tracing-spring-boot-autoconfigure` | `OtelDirectIntegrationArchTest`, `KafkaOutboundNoSpanArchTest` |
| `platform-tracing-autoconfigure-webmvc` | `ServletOutboundNoSpanArchTest` |

---

## 5. E2E tests (Docker required)

Full e2e module:

```powershell
./gradlew :platform-tracing-e2e-tests:test -PrunE2e
```

**Requires:** Docker daemon, Testcontainers, network for Collector/Jaeger images.

Critical PR-0 smoke (minimum):

```powershell
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*RuntimeSamplingControlSmokeTest*"
```

Additional high-value smokes (optional but recommended):

```powershell
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*ExceptionEventScrubbingE2ETest*"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*BspDropOldestSafetyAgentSmokeTest*"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*ClassLoaderVisibilitySpikeE2ETest*"
```

See [platform-tracing-e2e-tests/README.md](../../platform-tracing-e2e-tests/README.md).

---

## 6. JMH micro-benchmarks

Full suite (16 benchmark classes; ~3–5 min per class on reference lab):

```powershell
./gradlew :platform-tracing-bench:jmh
```

Save baseline for current hardware profile:

```powershell
./gradlew :platform-tracing-bench:jmhSaveBaseline
```

**Output:**

- Run: `platform-tracing-bench/build/results/jmh/results.json`
- Baseline: `platform-tracing-bench/baselines/<hardwareProfileId>/results.json` + `baseline-metadata.json`

Optional single-class focus (names unchanged — do not rename for migration):

```powershell
./gradlew :platform-tracing-bench:jmh -PjmhInclude=CompositeSamplerBenchmark
./gradlew :platform-tracing-bench:jmh -PjmhInclude=ScrubbingEngineBenchmark
./gradlew :platform-tracing-bench:jmh -PjmhInclude=CompositeSamplerConcurrentUpdateBenchmark
```

Smoke only (not for baseline numbers):

```powershell
./gradlew :platform-tracing-bench:jmh -PjmhQuickSmoke
```

Compare after future PRs:

```powershell
./gradlew :platform-tracing-bench:jmhCompareBaseline
./gradlew :platform-tracing-bench:jmhCompareBaseline -PjmhFailOnRegression
# Optional strict sample-mode gate (investigation only):
./gradlew :platform-tracing-bench:jmhCompareBaseline -PjmhFailOnRegression -PjmhFailOnSampleRegression=true
```

Hard gate: avgt latency + alloc. Sample-mode latency is diagnostic by default (PR-6C3).

See [docs/tracing/jmh-suite.md](../tracing/jmh-suite.md).

---

## 7. PerformanceReleaseGateTest

Release gate (hard budgets must be CLOSED for release; **PENDING is OK for PR-0 baseline recording**):

```powershell
./gradlew :platform-tracing-bench:performanceReleaseGate
```

Contract tests (always run in normal `test`):

```powershell
./gradlew :platform-tracing-bench:test --tests "*PerformanceBudgetsContractTest*"
./gradlew :platform-tracing-bench:test --tests "*PerformanceReleaseGateTest*"
```

Note: `PerformanceReleaseGateTest` is **disabled** unless `-Dplatform.release.gate=true` (set only by `performanceReleaseGate` task).

---

## 8. Macro perf scenarios

Scripts: `platform-tracing-perf-tests/scripts/run-perf-scenario.ps1`  
Artifacts: `docs/tracing/perf-results/<stamp>/<scenario>/`

### M0 — host calibration (run twice before official runs)

```powershell
cd platform-tracing-perf-tests
.\scripts\run-perf-scenario.ps1 -Scenario m0
.\scripts\run-perf-scenario.ps1 -Scenario m0
```

Remote reference lab example:

```powershell
.\scripts\run-perf-scenario.ps1 -Scenario m0 -DockerHost tcp://192.168.100.70:2375
```

### M5 — agent + extension + export delta

```powershell
.\scripts\run-perf-scenario.ps1 -Scenario m5 -DockerHost tcp://192.168.100.70:2375
```

With JFR (recommended when CPU delta > 2%):

```powershell
.\scripts\run-perf-scenario.ps1 -Scenario m5 -Jfr -DockerHost tcp://192.168.100.70:2375
```

### M10 / M10c / M10d — config reload / runtime mutation under load

```powershell
.\scripts\run-perf-scenario.ps1 -Scenario m10
.\scripts\run-perf-scenario.ps1 -Scenario m10c
.\scripts\run-perf-scenario.ps1 -Scenario m10d
```

M10 uses SUT `PerfAdminController` → JMX bridge to `PlatformTracingControl` (perf-only, not production).

Analysis vs M0 baseline:

```powershell
.\scripts\analyze-perf-run.ps1 -RunDir ..\..\docs\tracing\perf-results\<stamp>\m5 `
                               -BaselineDir ..\..\docs\tracing\perf-results\<stamp>\m0
```

See [platform-tracing-perf-tests/README.md](../../platform-tracing-perf-tests/README.md).

---

## 9. Dependency snapshot

Automated (PR-0 task):

```powershell
./gradlew pr0DependencySnapshot
```

Manual per-module inspection (optional):

```powershell
./gradlew :platform-tracing-spring-boot-starter-servlet:dependencies --configuration compileClasspath
./gradlew :platform-tracing-spring-boot-starter-reactive:dependencies --configuration compileClasspath
./gradlew :platform-tracing-otel-extension:dependencies --configuration compileClasspath
```

**Modules captured:**

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

**Purpose:** detect accidental dependency graph changes in later PRs.

---

## 10. Starter dependency smoke

```powershell
./gradlew pr0StarterDependencySmoke
```

**Verifies:**

- Servlet starter → `autoconfigure` + `webmvc`; no `webflux`; no `otel-extension` on classpath.
- Reactive starter → `autoconfigure` + `webflux`; no `webmvc`; no `otel-extension` on classpath.

---

## 11. Fill results report

1. Copy [pr-0-baseline-results-template.md](pr-0-baseline-results-template.md) → `pr-0-baseline-results-<YYYY-MM-DD>.md`.
2. Paste command outputs / links to artifacts.
3. List known failures (M5 budget, PENDING release gate) under **Known failures**.
4. Commit baseline artifacts + results report.

---

## PR-0 completion checklist

| # | Item | Done |
|---|------|------|
| 1 | `./gradlew pr0BaselineLock` or equivalent automated steps green | ☐ |
| 2 | `./gradlew test` / `check` green | ☐ |
| 3 | ArchUnit tests green | ☐ |
| 4 | E2E `-PrunE2e` (min. `RuntimeSamplingControlSmokeTest`) | ☐ |
| 5 | JMH full run + `jmhSaveBaseline` | ☐ |
| 6 | `performanceReleaseGate` outcome recorded | ☐ |
| 7 | M0 captured (×2) | ☐ |
| 8 | M5 captured | ☐ |
| 9 | M10/M10c/M10d captured (if infra available) | ☐ |
| 10 | Dependency snapshot committed | ☐ |
| 11 | Starter smoke report committed | ☐ |
| 12 | Results report filled | ☐ |

---

*No production code changes. No tests deleted. No modules collapsed.*
