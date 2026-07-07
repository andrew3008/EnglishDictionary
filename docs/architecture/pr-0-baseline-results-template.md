# PR-0 baseline results — TEMPLATE

> Copy this file to `pr-0-baseline-results-<YYYY-MM-DD>.md` and fill after running [pr-0-baseline-checklist.md](pr-0-baseline-checklist.md).

---

## Environment

| Field | Value |
|-------|-------|
| **Git commit** | |
| **Git short SHA** | |
| **Date** | |
| **Operator** | |
| **Machine / environment** | |
| **Java version** | |
| **Gradle version** | |
| **OS** | |
| **CPU** | |
| **Memory** | |
| **Docker available** | yes / no |
| **Reference perf lab used** | yes / no (Gentoo / other) |

---

## Unit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test` | PASS / FAIL | |
| `./gradlew check` | PASS / FAIL | |

**Failed tests (if any):**

```text
(paste test class / module)
```

---

## ArchUnit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test --tests "*Arch*Test"` | PASS / FAIL / SKIPPED | |

**Modules verified:**

```text
platform-tracing-test
platform-tracing-core
platform-tracing-otel-extension
platform-tracing-spring-boot-autoconfigure
platform-tracing-autoconfigure-webmvc
```

---

## E2E tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-e2e-tests:test -PrunE2e` | PASS / FAIL / SKIPPED | Docker required |
| `RuntimeSamplingControlSmokeTest` | PASS / FAIL / SKIPPED | CRITICAL for PR-0 |

**E2E count (if full run):** ___ / 42 tests

**Artifact / log path:**

```text
```

---

## JMH baseline result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-bench:jmh` | PASS / FAIL | |
| `./gradlew :platform-tracing-bench:jmhSaveBaseline` | PASS / FAIL | |

**Hardware profile ID:** `platform-tracing-bench/baselines/<profileId>/`

**Baseline paths:**

```text
platform-tracing-bench/baselines/<profileId>/results.json
platform-tracing-bench/baselines/<profileId>/baseline-metadata.json
platform-tracing-bench/build/results/jmh/results.json
```

**Hot-path benchmarks run (check all that apply):**

- [ ] `CompositeSamplerBenchmark`
- [ ] `CompositeSamplerPolicyBranchesBenchmark`
- [ ] `CompositeSamplerConcurrentUpdateBenchmark`
- [ ] `ScrubbingEngineBenchmark`
- [ ] `ScrubbingPerRuleBenchmark`
- [ ] `QueueOfferBenchmark`
- [ ] `CompositePipelineBenchmark`
- [ ] Other: ___

---

## PerformanceReleaseGateTest result

| Command | Result | Expected for PR-0 |
|---------|--------|-------------------|
| `./gradlew :platform-tracing-bench:performanceReleaseGate` | PASS / FAIL | FAIL acceptable if hard budgets `PENDING` |
| `./gradlew :platform-tracing-bench:test --tests "*PerformanceBudgetsContractTest*"` | PASS / FAIL | |

**Notes:**

```text
(e.g. hard budgets PENDING per performance-budgets.yaml — documented pre-release state)
```

---

## Macro perf results

### M0 — host calibration

| Run | Path | Result | Notes |
|-----|------|--------|-------|
| M0 run 1 | `docs/tracing/perf-results/<stamp>/m0/` | VALID / INVALID / SKIPPED | |
| M0 run 2 | `docs/tracing/perf-results/<stamp>/m0/` | VALID / INVALID / SKIPPED | |

### M5 — agent + extension + export delta

| Field | Value |
|-------|-------|
| **Path** | `docs/tracing/perf-results/<stamp>/m5/` |
| **Result** | VALID / INVALID / SKIPPED |
| **Δ CPU vs M0** | |
| **Δ RSS vs M0** | |
| **Documented baseline FAIL** | yes (+48% CPU, +25% RSS) / no / N/A |

### M10 / M10c / M10d — config reload / runtime mutation

| Scenario | Path | Result | Notes |
|----------|------|--------|-------|
| M10 | | VALID / INVALID / SKIPPED | |
| M10c | | VALID / INVALID / SKIPPED | |
| M10d | | VALID / INVALID / SKIPPED | |

---

## Dependency snapshot location

| Item | Path |
|------|------|
| Snapshot directory | `docs/architecture/baselines/pr-0/dependency-snapshot/` |
| Metadata | `docs/architecture/baselines/pr-0/dependency-snapshot-metadata.json` |
| Gradle task | `./gradlew pr0DependencySnapshot` |
| Date captured | |

**Modules snapshotted:**

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

---

## Starter dependency smoke

| Command | Result |
|---------|--------|
| `./gradlew pr0StarterDependencySmoke` | PASS / FAIL |

**Report path:** `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt`

**Expectations confirmed:**

- [ ] Servlet apps → `platform-tracing-spring-boot-starter-servlet` only ( + BOM )
- [ ] Reactive apps → `platform-tracing-spring-boot-starter-reactive` only ( + BOM )
- [ ] No direct app dependency on `otel-extension` via starter classpath
- [ ] Stack isolation: servlet starter without webflux; reactive without webmvc

---

## Automated PR-0 gate

| Command | Result |
|---------|--------|
| `./gradlew pr0BaselineLockAutomated` | PASS / FAIL |
| `./gradlew pr0BaselineLock` | PASS / FAIL |

---

## Known failures

Document **expected** failures here — do not hide committee-known state.

```text
Example:
- M5 macro perf FAIL vs CPU/RSS budget (documented 2026-06-10_official)
- performanceReleaseGate FAIL — hard budgets PENDING in performance-budgets.yaml
```

---

## Known risks

```text
(e.g. Actuator MUTATION exposed in prod without guard — migration PR-11)
(e.g. platform-tracing-core has api opentelemetry-api — MIGRATION_RISK)
```

---

## Manual review required

```text
(Requires manual review items from inventory / migration plan)
```

---

## Sign-off

| Role | Name | Date | PR-0 baseline accepted |
|------|------|------|------------------------|
| Operator | | | ☐ |
| Platform engineer | | | ☐ |
| SRE (perf/e2e) | | | ☐ |

---

*Template for PR-0 only. Does not approve production rollout or architecture changes.*
