# PR-0 baseline results — 2026-06-12 (complete with waivers)

> Evidence-first report assembled by Cursor Composer 2.5.  
> Prior drafts: [pr-0-baseline-results-2026-06-11.md](pr-0-baseline-results-2026-06-11.md), [pr-0-baseline-results-2026-06-12-final.md](pr-0-baseline-results-2026-06-12-final.md)  
> Checklist: [pr-0-baseline-checklist.md](pr-0-baseline-checklist.md)

## PR-0 baseline status: COMPLETE_WITH_WAIVERS

PR-0 automated gate, dependency snapshot, starter smoke, workspace fingerprint, E2E critical smoke, and `performanceReleaseGate` outcome are recorded. Full JMH baseline was **not** completed in this interactive session and is **explicitly deferred** with a hard gate before hot-path extraction PRs.

---

## Completion criteria

| Criterion | Result | Evidence |
|-----------|--------|----------|
| Git SHA | **WAIVED** | Non-git workspace; see [Git SHA unavailable](#git-sha-unavailable) |
| Workspace fingerprint | **PASS** | `docs/architecture/baselines/pr-0/workspace-fingerprint.json` |
| `./gradlew pr0BaselineLock` | **PASS** | BUILD SUCCESSFUL 2026-06-12 (~9s) |
| Dependency snapshot | **PASS** | 8 modules under `docs/architecture/baselines/pr-0/dependency-snapshot/` |
| Starter smoke | **PASS** | `starter-dependency-smoke.txt` — PASSED (2026-06-12 14:57:55 MSK) |
| `RuntimeSamplingControlSmokeTest` | **PASS** | 1 test, 0 failures; report below |
| Full JMH baseline | **DEFERRED** | JMH-BASELINE-FINALIZATION 2026-06-12: batch 2/15 valid, baseline not saved — `perf-baseline-finalization-report.md` |
| JMH quick smoke | **PASS** (post-fix) | `jmh-quick-smoke-after-fix.log` |
| `performanceReleaseGate` | **FAIL_EXPECTED_PENDING_BUDGETS** | Task executed; 1/2 tests failed |
| Macro perf | **RECORDED** | Official session M0/M5/M10 + fresh M10c attempt (INVALID) |

---

## Environment

| Field | Value |
|-------|-------|
| **Git commit** | N/A — non-git workspace baseline |
| **Git short SHA** | N/A — non-git workspace baseline |
| **Baseline identity** | workspace fingerprint (`workspace-fingerprint.json`) |
| **Date** | 2026-06-12 |
| **Operator** | Cursor Composer 2.5 |
| **Machine / environment** | `DESKTOP-OEGBG7F` (Windows dev host) |
| **Java version** | 21.0.6 (Oracle JDK, build 21.0.6+8-LTS-188) |
| **Gradle version** | 8.14 (revision 34c560e3be961658a6fbcd7170ec2443a228b109) |
| **OS** | Windows 10 (10.0.19045.0) |
| **CPU** | 13th Gen Intel(R) Core(TM) i5-13500 (14 cores, 20 logical) |
| **Memory** | ~63.7 GB (68448153600 bytes) |
| **Docker available** | Gentoo reference lab `tcp://192.168.100.70:2375` — reachable (E2E PASS, macro perf executed) |
| **Reference perf lab used** | yes — official session `2026-06-10_official` + fresh M10c attempt `2026-06-12_145840` |

---

## Workspace fingerprint

**Path:** `docs/architecture/baselines/pr-0/workspace-fingerprint.json`

| Key file | SHA-256 (partial) |
|----------|-------------------|
| `settings.gradle` | `89a77739…efa093d` |
| `build.gradle` | `3184a0a4…a72f008` |
| `gradle.properties` | `7d69c2ab…a5ca1735` |
| `dependency-snapshot-metadata.json` | `0a45bdfb…3d93e81` |
| `starter-dependency-smoke.txt` | `bc890bde…252f5bb` |
| `gradle/libs.versions.toml` | missing |

---

## Unit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test` | PASS (via gate) | Subproject `:test` executed inside `pr0BaselineLockAutomated` |
| `./gradlew check` | PASS | Via `pr0BaselineLockAutomated` BUILD SUCCESSFUL |

**Module reports:** 0 failures across api, core, otel-extension, autoconfigure, webmvc, webflux, test, bench, collector-config.

---

## ArchUnit tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew test --tests "*Arch*Test"` | NOT RUN (dedicated) | Covered by module `:test` — 13 Arch*Test reports, 0 failures |

**Outcome:** PASS (inferred from parent module reports).

---

## E2E tests result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*RuntimeSamplingControlSmokeTest*"` | **PASS** | BUILD SUCCESSFUL (~29s); `$env:DOCKER_HOST=tcp://192.168.100.70:2375` |
| `RuntimeSamplingControlSmokeTest` | **PASS** | 1 test, 0 failures, 10.639s |

**Report path:** `platform-tracing-e2e-tests/build/reports/tests/test/classes/space.br1440.platform.tracing.e2e.smoke.RuntimeSamplingControlSmokeTest.html`

**Full E2E suite (42 tests):** NOT RUN — only critical smoke executed for PR-0.

---

## JMH baseline result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-bench:jmh` | **DEFERRED** | Prior run OOM at ~1.3% (`AttributePolicyBenchmark.warnMissingRequired`); log **2.83 GB** DEBUG flood → Gradle daemon OOM. See `perf-baseline-fix-report.md` |
| `./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g` (full) | **NOT RUN** | Reproducibility fix verified; full single-run deferred (~4h ETA on dev host) |
| `./gradlew :platform-tracing-bench:jmhSaveBaseline` | **NOT RUN** | Requires completed full merged `jmh` |
| `./gradlew :platform-tracing-bench:jmh -PjmhQuickSmoke` | **PASS** (2026-06-12 post-fix) | Log: `docs/architecture/baselines/pr-0/jmh-quick-smoke-after-fix.log` |
| Diagnostic `warnMissingRequired` `-PjmhHeap=4g` | **PASS** | 3m37s; log: `jmh-diagnostic-warnMissingRequired.log` |
| `./gradlew :platform-tracing-bench:jmhRunBaselineBatches -PjmhHeap=4g` | **IN PROGRESS** (2/15 valid at finalization) | Log: `jmh-batch-baseline.log`; see `perf-baseline-finalization-report.md` |
| `./gradlew :platform-tracing-bench:jmhValidateBatchResults` | **FAIL** (expected) | 2 present, 12 missing, 1 in-progress — `validated-manifest.json` |
| `./gradlew :platform-tracing-bench:jmhSaveBaseline` | **NOT RUN** | Requires merged full baseline |

**Status:** `JMH_BASELINE_STILL_BLOCKED` (JMH-BASELINE-FINALIZATION, 2026-06-12)

**Root cause (prior failure):** Logback default DEBUG + `AttributePolicy` repeat-violation logging → stdout captured by Gradle daemon (2 GiB heap). Fix: `platform-tracing-bench/src/jmh/resources/logback.xml` (ROOT WARN) + `-PjmhHeap=4g`.

**Partial evidence:**

```text
docs/architecture/baselines/pr-0/jmh-run.log                    — prior failed run (~2.83 GB)
docs/architecture/baselines/pr-0/jmh-diagnostic-warnMissingRequired.log — PASS
docs/architecture/baselines/pr-0/jmh-quick-smoke-after-fix.log  — PASS
docs/architecture/baselines/pr-0/jmh-batch-baseline.log         — batched run (in progress, class 3/15)
docs/architecture/perf-baseline-finalization-report.md        — finalization status (JMH_BASELINE_STILL_BLOCKED)
docs/architecture/perf-baseline-fix-report.md                   — engineering report
platform-tracing-bench/build/results/jmh/results.json           — diagnostic/smoke only (not full baseline)
platform-tracing-bench/baselines/<profileId>/                     — NOT FOUND
```

**Hot-path benchmarks:** source present; full official baseline numbers not yet saved via `jmhSaveBaseline`.

---

## PerformanceReleaseGateTest result

| Command | Result | Notes |
|---------|--------|-------|
| `./gradlew :platform-tracing-bench:performanceReleaseGate` | **FAIL_EXPECTED_PENDING_BUDGETS** | BUILD FAILED; 2 tests, 1 failure |
| `./gradlew :platform-tracing-bench:test --tests "*PerformanceBudgetsContractTest*"` | PASS (via `:test`) | 10 tests, 0 failures |

**Failed test:** `PerformanceReleaseGateTest.все_hard_бюджеты_закрыты_evidence_или_waiver()`  
**Report:** `platform-tracing-bench/build/reports/tests/performanceReleaseGate/index.html`

**Interpretation:** expected pre-release state — hard budgets `PENDING` in `performance-budgets.yaml`.

---

## Macro perf results

### Official baseline session (reference lab)

**Path:** `docs/tracing/perf-results/2026-06-10_official/`  
**Aggregate:** `session-summary.json`, `architecture-committee-review.md`

| Scenario | Result | Key metrics |
|----------|--------|-------------|
| M0 | VALID (×4 valid of 6) | Baseline `2026-06-10_235521/m0`; calibration noise 1.77% CPU |
| M5 | VALID (median) | Δ CPU **+48.14%**, Δ RSS **+25.37%** vs M0 — **documented FAIL** vs budget |
| M10 | VALID (×3) | Median Δ CPU +44.95%, Δ RSS +20.97% |
| M10c | SKIPPED — evidence not found | Not in official session |
| M10d | SKIPPED — evidence not found | Not in official session |

### Fresh run (2026-06-12)

| Scenario | Path | Result | Notes |
|----------|------|--------|-------|
| M10c | `docs/tracing/perf-results/2026-06-12_145840/m10c/` | **INVALID** | `runValid: false`, `k6ExitCode: 1`; log: `docs/architecture/baselines/pr-0/m10c-run.log` |
| M10d | — | **WAIVED** | Not executed; required before PR-10/PR-11/PR-12 if not covered by official session |

**Macro perf summary:** official session used for M0/M5/M10 baseline; fresh M10c attempted but invalid; M10d waived for PR-1 entry.

---

## Dependency snapshot location

| Item | Path |
|------|------|
| Snapshot directory | `docs/architecture/baselines/pr-0/dependency-snapshot/` |
| Metadata | `docs/architecture/baselines/pr-0/dependency-snapshot-metadata.json` |
| Gradle task | `./gradlew pr0DependencySnapshot` |
| Date captured | 2026-06-12T14:19:49+03:00 |

**Modules (8):** api, core, otel-extension, spring-boot-autoconfigure, autoconfigure-webmvc, autoconfigure-webflux, starter-servlet, starter-reactive.

**Result:** PASS

---

## Starter dependency smoke

| Command | Result |
|---------|--------|
| `./gradlew pr0StarterDependencySmoke` | **PASS** |

**Report:** `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt` — PASSED (2026-06-12 14:57:55 MSK)

---

## Automated PR-0 gate

| Command | Result |
|---------|--------|
| `./gradlew pr0BaselineLockAutomated` | **PASS** — BUILD SUCCESSFUL |
| `./gradlew pr0BaselineLock` | **PASS** — BUILD SUCCESSFUL |

**Note:** `validateCollectorConfigs` may log Docker volume-mount errors from Windows→remote daemon; ignored without `-PstrictValidation`.

---

## Known failures

Expected baseline state — not PR-0 regressions:

```text
- M5 macro perf FAIL vs CPU/RSS budget (official session 2026-06-10_official):
  median Δ CPU +48.14%, Δ RSS +25.37% vs M0.
- performanceReleaseGate FAIL_EXPECTED_PENDING_BUDGETS — hard budgets PENDING.
- Fresh M10c run 2026-06-12_145840 INVALID (k6ExitCode 1) — recorded, not hidden.
- M0 host calibration borderline: 1.77% CPU drift vs 1.0% threshold (official session).
```

---

## Known risks

```text
- Actuator MUTATION exposed in prod without guard — migration PR-11.
- platform-tracing-core has api opentelemetry-api — MIGRATION_RISK.
- TracingConfigReconciler not found in current codebase — target-only.
- Full JMH baseline not captured — hot-path extraction blocked until captured.
```

---

## Waivers / Deferred evidence

### Git SHA unavailable

Status: WAIVED_BY_WORKSPACE_CONSTRAINT

Reason:
This workspace is not a real Git checkout and Git SHA is unavailable by design.

Replacement evidence:
- workspace-fingerprint.json
- dependency-snapshot-metadata.json
- starter-dependency-smoke.txt
- environment capture

### Full JMH baseline deferred

Status: `JMH_BASELINE_STILL_BLOCKED` (JMH-BASELINE-FINALIZATION, 2026-06-12)

Reason (original): full JMH single-run ETA ~4h; prior run failed with Gradle daemon OOM during `warnMissingRequired` (2.83 GB DEBUG log flood).

Fix applied (harness only):
- `platform-tracing-bench/src/jmh/resources/logback.xml` (ROOT WARN)
- `-PjmhHeap=4g` (default), `-PjmhResultFile`, `-PjmhProfileId`, `-PjmhExclude`, `jmhRunBaselineBatches`

Hard gate (unchanged):
Before PR-6/PR-7/PR-8 or any sampling/scrubbing/validation/enrichment extraction, full JMH baseline must be captured with:

```powershell
./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g
./gradlew :platform-tracing-bench:jmhSaveBaseline
```

Or approved `FULL_BASELINE_BATCHED` manifest (`jmhRunBaselineBatches`) with all 15 benchmark classes PASS.

Until that baseline exists, no hot-path extraction is allowed. PR-5 (tests only) may proceed without full baseline.

### Fresh macro perf M10d

Status: WAIVED_FOR_PR-1_ONLY

Reason:
M10/M5/M0 covered by official reference-lab session; fresh M10c attempt recorded but INVALID; M10d not required to start PR-1 (documentation/guardrails only).

Required before: PR-10/PR-11/PR-12 (config reload / runtime mutation under load).

### Full E2E suite (42 tests)

Status: WAIVED_FOR_PR-0

Reason:
Critical smoke `RuntimeSamplingControlSmokeTest` PASS recorded; full suite deferred to reduce interactive session time.

---

## Hard gates before refactoring

The following must be completed before PR-5/PR-6/PR-7/PR-8:

1. **Full JMH baseline:**
   ```powershell
   ./gradlew :platform-tracing-bench:jmh
   ./gradlew :platform-tracing-bench:jmhSaveBaseline
   ```
   Artifacts required:
   - `platform-tracing-bench/build/results/jmh/results.json`
   - `platform-tracing-bench/baselines/<profileId>/results.json`
   - `platform-tracing-bench/baselines/<profileId>/baseline-metadata.json`

2. **RuntimeSamplingControlSmokeTest result:** **DONE (PASS)** — re-run if code changes before extraction.

3. **performanceReleaseGate outcome:** **DONE (FAIL_EXPECTED_PENDING_BUDGETS)** — re-run before release sign-off.

4. **No sampling/scrubbing/validation/enrichment extraction** before full JMH baseline is captured.

---

## Manual review required

```text
- Terminate stale JMH process holding jmh.lock before next JMH run (if still running).
- Re-run full E2E suite (-PrunE2e) before production sign-off.
- Investigate fresh M10c INVALID run (k6ExitCode 1) if M10c becomes release gate.
- performanceReleaseGate remains FAIL until hard budgets closed or waived per governance.
```

---

## Next commands to run

Before PR-5/PR-6/PR-7/PR-8 (not blocking PR-1):

```powershell
# Remove stale JMH lock if needed, then full baseline
./gradlew :platform-tracing-bench:jmh
./gradlew :platform-tracing-bench:jmhSaveBaseline
./gradlew :platform-tracing-bench:jmhCompareBaseline

# Full E2E
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e

# Macro perf gaps
cd platform-tracing-perf-tests
.\scripts\run-perf-scenario.ps1 -Scenario m10d -DockerHost tcp://192.168.100.70:2375
```

---

## Sign-off

| Role | Name | Date | PR-0 baseline accepted (with waivers) |
|------|------|------|--------------------------------------|
| Operator | | | ☐ |
| Platform engineer | | | ☐ |
| SRE (perf/e2e) | | | ☐ |

---

*PR-0 baseline status: COMPLETE_WITH_WAIVERS (2026-06-12). PR-1 may proceed under documented waivers. Hot-path extraction blocked until full JMH baseline captured.*
