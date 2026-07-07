# 08 — Test and Release Gates Refresh

> Evidence refresh for V6-clean-lite PR sequence.
> Sources: plan §5, §6, §8; dossier 05-test-coverage-and-gaps.md.
> Do NOT modify production code on the basis of this file.

---

## Governing Rule

**Disabled tests document known failures; they cannot hide gaps to move forward.**

A `@Disabled` test is only acceptable when:
1. It is accompanied by a reference to a filed issue or ADR (e.g., `@Disabled("known failure R3 — tracked in issue #NNN, fixed in PR-3")`).
2. The disabled test does not represent a gate for the *current* PR. If a test must be green before a PR merges, it CANNOT be `@Disabled` at that PR's merge point.
3. The test does not hang. A disabled test that would deadlock the test runner if enabled is still unacceptable — it must be written with bounded-timeout assertions.

---

## PR-0 Gates — Characterization Tests + JMH Baseline

PR-0 introduces only tests and benchmarks. Zero production code changes. All gates must pass before PR-0 merges.

| Gate | Test label | Invariant / Risk covered | Determinism strategy | Allowed state at PR-0 merge |
|------|-----------|--------------------------|---------------------|----------------------------|
| M1 | Concurrent `forceFlush` + `shutdown` | R3 — forceFlush promise may not complete | `CountDownLatch`/`CyclicBarrier` start, bounded-timeout assertion on every promise | Active (green) OR `@Disabled` with issue ref if genuinely deadlocking |
| M2 | Multiple concurrent `forceFlush` | `pendingFlushes` batching under concurrent callers | `CyclicBarrier` starts N threads simultaneously | Active (green) OR `@Disabled` with issue ref |
| M3 | `exporter.shutdown()` hang | R1 — no timeout on `exporter.shutdown()` | `NeverCompleteShutdownExporter`; **MUST use bounded `join(timeout)` / `get(timeout)` — never `join()` without bound** | `@Disabled` with issue ref is acceptable; documents known gap (R1) |
| M4a | Exporter exception isolation | Worker continues after export exception | `ThrowingExporter(callCount)` injects on nth call | Active (must be green) |
| M6 | Shutdown-timeout interrupt path | R2 (indirectly) — interrupt path correctness | Short `shutdownTimeout`, `NeverCompletingResultCodeExporter`; bounded assertion `shutdown completes within 2×shutdownTimeout` | Active (green) or `@Disabled` with issue ref if integration proves unstable |
| M7 | `forceFlush` after completed shutdown | Post-shutdown forceFlush → `ofSuccess()` | Call after `shutdown().join(timeout)` | Active (must be green) |
| M10 | Drop-oldest invariants | Drop-oldest preserves newest + non-blocking `onEnd` + exporter outside lock | `BlockingExporter` + barrier + capacity=N | Active (must be green) |
| M11 | JMH baseline artifact | Performance regression prevention | `QueueOfferBenchmark` per §11 procedure | Baseline artifact saved to CI/repository |

**Additional active tests (no excuse for @Disabled):**
- M4b (worker-loop death via white-box hook) — only if hook is achievable without production code change; skip otherwise (analysis-only)
- M5 (scheduleDelay deterministic trigger) — active, uses `sleep(2 × delay)`
- M8 (`toSpanData()` RuntimeException) — active, uses stub span
- M9 (log throttling) — active, uses SLF4J test appender

**Phase 0 Failure Decision Policy:**
- If M1/M2 reveal a live `CompletableResultCode` that never completes: file an issue, add `@Disabled("known failure R3 — issue #NNN")`, confirm test does not hang in disabled state. PR-1/PR-2 may start; **PR-3 MUST make M1/M2 green before merging**.
- If M6 is unstable as an integration test: file an issue, add `@Disabled`, PR-3 must stabilize it. **Do not weaken the assertion**.
- A test is NEVER made green by weakening assertions or altering production behavior.

---

## PR-1 Gates — Minimal Components

PR-1 introduces only: `DropOldestExportProcessorConfig`, `TimedSpanExporter`, `DropOldestProcessorMetrics`, `DropOldestProcessorMetricsSnapshot` with unit tests.

| Gate | Requirement |
|------|------------|
| Config unit tests | `DropOldestExportProcessorConfig` — validation, fallback logic, `otel.bsp.*` reading |
| TimedSpanExporter unit tests | Export timeout path, failure classification, `exportTimeouts ⊆ exportFailures` invariant |
| Metrics unit tests | `DropOldestProcessorMetrics` counter increments, `snapshot()` immutability |
| Existing tests | All PR-0 active tests must remain green |
| Factory untouched | `PlatformExportProcessorFactory` MUST NOT be modified in PR-1 |
| No partial wiring | New components are `package-private` units with no production path wiring |
| No speculative classes | No `ExportBuffer`, `ExportWorker`, `ProcessorLifecycle` created in PR-1 |

---

## PR-2 Gates — Buffer + Worker + Lifecycle

PR-2 introduces `ExportBuffer`, `ExportWorker`, `ProcessorLifecycle`; migrates logic; makes facade thin.

| Gate | Requirement |
|------|------------|
| All PR-0 active tests | Must remain green (known failures R1/R3 may remain `@Disabled` with refs) |
| M10 | Must be green — drop-oldest invariants must survive the structural migration |
| Legacy getters | Facade may temporarily keep `getDroppedSpansOverflow()` etc. delegating to `metrics.snapshot()`; they must compile and return correct values |
| JMH baseline | No material regression vs PR-0 captured baseline (same procedure §11) |
| Lock ownership | `ExportBuffer` owns `queueLock`; no other class acquires it; verified by code review |
| `awaitWork()` contract | `ExportBuffer.awaitWork()` releases lock before returning `ExportWork` |

---

## PR-3 Gates — Clean Shutdown / ForceFlush Semantics (STRICT)

**PR-3 cannot merge while M1 or M2 are `@Disabled`.**

| Gate | Status requirement | Rationale |
|------|-------------------|-----------|
| **M1** (concurrent forceFlush + shutdown) | **MUST be green** | The forceFlush promise race (R3) is the primary motivator for PR-3. If PR-3 does not fix it, the PR has not met its stated goal. |
| **M2** (multiple concurrent forceFlush) | **MUST be green** | Demonstrates that the new `ExportBuffer.awaitWork()` contract drains all flush promises correctly under concurrency. |
| **M6** (shutdown-timeout interrupt path) | **MUST be green** | Bounded shutdown is a PR-3 invariant; the interrupt path must be stable. |
| M3 (exporter.shutdown() hang) | May remain `@Disabled` with bounded-timeout assertion (documents remaining gap if exporter.shutdown() is still unbounded) | See note below |
| `shutdownResult` bounded completion | `shutdownResult` must complete (success or fail) even when `exporter.shutdown()` hangs — requires a timeout on the `exporter.shutdown()` call | Integration test with `NeverCompleteShutdownExporter` must not hang the test suite |
| ADR update | PR-3 acceptance criteria require documenting "bounded shutdown" and "deterministic forceFlush" as observable semantic changes in the ADR | |
| No incomplete `CompletableResultCode` | Stress variant of M1/M2 must run without any promise stuck indefinitely | |

**Note on M3:** if PR-3 adds a bounded timeout to `exporter.shutdown()`, M3 becomes the test that validates that bound. It must then be activated (not disabled). If `exporter.shutdown()` timeout is deferred to a later PR, M3 remains `@Disabled` but PR-3 cannot claim "bounded shutdown" without it.

---

## PR-4 Gates — Replace Old API / JMX / Builder

| Gate | Requirement |
|------|------------|
| Opt-in activation test | `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=DROP_OLDEST` routes to `PlatformDropOldestExportSpanProcessor` (new wiring) |
| Default UPSTREAM passthrough test | No policy set (or `UPSTREAM`) → stock BSP is used, NOT the custom processor |
| Multi-exporter fallback test | Multi-exporter configuration falls back to stock BSP without the custom processor |
| No-double-export test | `BspDropOldestNoDoubleExportTest` must remain green with new factory wiring |
| JMX adapter test | All `MetricsSnapshot` fields are accessible as JMX attributes via `PlatformExportControl`; new attribute names match snapshot field names |
| **Rollback test** | **`PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` unset after opt-in → factory routes to stock BSP, not custom processor** |
| Legacy getters removed | `getDroppedSpansOverflow()` etc. removed from facade; `PlatformExportControl` reads `MetricsSnapshot` directly |
| New JMX schema documented | Operator contract document (`MetricsSnapshot` → JMX attribute mapping) merged in same PR |

---

## PR-5 Gates — Remove Legacy / Transitional Debris

| Gate | Requirement |
|------|------------|
| No dead code | No bridge classes, deprecated methods, or transitional adapters remain |
| All tests green | The full test suite including M1–M11 must be green |
| No `@Deprecated` annotations remaining | Any previously deprecated method introduced as a transitional bridge in PR-2/PR-4 must be removed |

---

## PR-6 Gates — JMH + Stress Validation

| Gate | Requirement |
|------|------------|
| JMH regression | No material regression vs PR-0 captured baseline (same machine, JDK, JVM flags, JMH parameters per §11) |
| Stress: no deadlock | Mixed `onEnd` + `forceFlush` + `shutdown` load — no thread hangs |
| Stress: no incomplete promise | No `CompletableResultCode` left permanently incomplete |
| Stress: no lock-leak | Worker does not hold `queueLock` across export call |
| CI artifact published | Raw JMH output + summary published as PR-6 artifact; diff vs PR-0 baseline included |
