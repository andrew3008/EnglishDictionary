# PR-0 — Baseline lock: behavior, performance, dependency snapshot

## Document status

```text
Status: PR-0 implementation planning / baseline capture guide.
Scope: Lock current codebase state before Clean Core Hybrid migration.
This PR does not change production behavior.
This PR does not start extraction or refactoring.
```

**Related documents:**

- [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md)
- [platform-tracing-current-codebase-inventory.md](platform-tracing-current-codebase-inventory.md)
- [pr-0-baseline-checklist.md](pr-0-baseline-checklist.md)
- [pr-0-baseline-results-template.md](pr-0-baseline-results-template.md)

---

## Purpose

PR-0 фиксирует **текущее** состояние Platform Tracing **до** любых migration PR (PR-1 … PR-14):

- все unit/integration/ArchUnit тесты;
- JMH micro-benchmark baseline;
- e2e smoke (включая `RuntimeSamplingControlSmokeTest`);
- macro perf сценарии M0/M5/M10*;
- dependency graph snapshot ключевых модулей;
- starter dependency expectations.

**Non-negotiable:** production Java code, `TracingProperties`, sampler/scrubbing/exporter, JMX, Actuator — **не меняются** в PR-0.

---

## What PR-0 changes in this repository

| Change type | Allowed in PR-0 |
|-------------|-----------------|
| Documentation (`docs/architecture/pr-0-*`) | yes |
| Gradle helper tasks (`pr0*`) | yes |
| Baseline artifact files under `docs/architecture/baselines/pr-0/` | yes (after running tasks) |
| Production Java code | **no** |
| Test deletion / rename | **no** |
| JMH benchmark rename / `@Param` change | **no** |
| Module collapse / package moves | **no** |

---

## Gradle helper tasks (added in PR-0)

| Task | What it does |
|------|----------------|
| `./gradlew pr0DependencySnapshot` | Writes resolved dependency trees for 8 migration modules → `docs/architecture/baselines/pr-0/dependency-snapshot/` |
| `./gradlew pr0StarterDependencySmoke` | Verifies servlet/reactive starter dependency expectations; writes `starter-dependency-smoke.txt` |
| `./gradlew pr0BaselineLockAutomated` | Runs all subproject `check` tasks (same as `./gradlew check`) + snapshot + starter smoke |
| `./gradlew pr0BaselineLock` | Runs automated checks + prints manual step reminders |

Existing tasks (unchanged behavior):

| Task | Module |
|------|--------|
| `jmh` | `:platform-tracing-bench` |
| `jmhSaveBaseline` | `:platform-tracing-bench` |
| `jmhCompareBaseline` | `:platform-tracing-bench` |
| `performanceReleaseGate` | `:platform-tracing-bench` |

---

## Baseline artifact locations

| Artifact | Path |
|----------|------|
| Dependency snapshot (per module) | `docs/architecture/baselines/pr-0/dependency-snapshot/<module>.txt` |
| Dependency snapshot metadata | `docs/architecture/baselines/pr-0/dependency-snapshot-metadata.json` |
| Starter smoke report | `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt` |
| JMH baseline (per hardware profile) | `platform-tracing-bench/baselines/<profileId>/results.json` |
| JMH run output | `platform-tracing-bench/build/results/jmh/results.json` |
| Macro perf runs | `docs/tracing/perf-results/<stamp>/<scenario>/` |
| PR-0 results report (filled by operator) | `docs/architecture/pr-0-baseline-results-<date>.md` (from template) |

---

## Starter dependency expectations (documented)

Application teams:

```text
Use BOM (platform-tracing-bom) + exactly one Spring Boot starter.
Servlet / Spring MVC  → platform-tracing-spring-boot-starter-servlet
WebFlux / Reactive    → platform-tracing-spring-boot-starter-reactive
Do NOT depend directly on:
  platform-tracing-core
  platform-tracing-otel-extension
  platform-tracing-spring-boot-autoconfigure
  platform-tracing-autoconfigure-webmvc
  platform-tracing-autoconfigure-webflux
```

PR-0 Gradle task `pr0StarterDependencySmoke` verifies:

- `starter-servlet` pulls `autoconfigure` + `webmvc`, not `webflux`, not `otel-extension` on classpath;
- `starter-reactive` pulls `autoconfigure` + `webflux`, not `webmvc`, not `otel-extension` on classpath.

---

## Known baseline facts (from inventory / committee evidence)

Record in results report; do not treat as failures if already documented:

- **M5 macro perf:** documented FAIL vs budget (+48% CPU, +25% RSS) — see `docs/tracing/perf-results/2026-06-10_official/`.
- **`PerformanceReleaseGateTest`:** expected **FAIL** while hard budgets in `performance-budgets.yaml` remain `PENDING` (pre-release state). PR-0 records the outcome; release gate green is not required for PR-0 completion.
- **`TracingConfigReconciler`:** Not found in current codebase (target-only).

---

## Acceptance criteria

PR-0 preparation is complete when:

1. Production code unchanged.
2. Tests not deleted; JMH names/`@Param` unchanged.
3. Checklist documents real Gradle/perf commands.
4. Template exists for baseline results.
5. Dependency snapshot task + instructions exist.
6. Starter smoke task or documented expectations exist.
7. Operator can run checklist and commit baseline artifacts.

---

## After PR-0

1. Run [pr-0-baseline-checklist.md](pr-0-baseline-checklist.md).
2. Fill [pr-0-baseline-results-template.md](pr-0-baseline-results-template.md) → save as `pr-0-baseline-results-<date>.md`.
3. Commit baseline artifacts (dependency snapshot, JMH baseline, perf results if captured).
4. Proceed to **PR-1** (module taxonomy + dependency guardrails) per preservation-first migration plan.

---

*PR-0 scope: baseline lock only. No migration, no refactoring, no module collapse.*
