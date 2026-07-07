# 01 — V6-clean-lite: Evidence Status

> Evidence-first. All citations are to actual repository files read on 2026-06-30.
> "Evidence missing" marks claims that have no current repository support.

---

## Evidence FROM the Repository Supporting Clean Decomposition

### 1. Nine Responsibilities Confirmed in Source

`PlatformDropOldestExportSpanProcessor.java` (537 lines) contains:

| Responsibility | Evidence (lines) |
|----------------|------------------|
| Bounded drop-oldest queue | `enqueueWithDropOldest()` lines 189–208; `drainBatchLocked()` lines 281–293 |
| Export worker lifecycle | `workerLoop()` lines 211–263; constructor line 67–70 |
| Timed batch export | `exportBatch()` lines 351–374; `resultCode.join(exportTimeoutNanos)` line 360 |
| forceFlush coordination | `forceFlush()` lines 113–128; `drainPendingFlushesLocked()` lines 296–302 |
| Shutdown coordination | `shutdown()` lines 131–187; terminator thread lines 143–185 |
| Builder + config validation | `Builder` class lines 430–536; `applyValidationWithSafeFallback()` lines 498–534 |
| BSP config key reading | `readBspConfigFrom()` lines 467–491 |
| Observability counters | `AtomicLong` fields lines 49–52; six getter methods lines 397–424 |
| Lifecycle state guard | `AtomicBoolean shutdownRequested` line 44 |

**Verdict:** nine distinct responsibilities in 537 lines — decomposition case is evidence-based.

### 2. R2 Double-Unlock Maintenance Hazard Confirmed

Non-standard locking pattern at lines 226–247:
- Explicit `queueLock.unlock()` at line 231 inside `InterruptedException` handler
- `finally` block guards with `if (queueLock.isHeldByCurrentThread())` at line 245

`02-concurrency-model.md` §Non-Standard Locking Pattern: "The explicit early unlock... is the highest-risk single code region in the class." Plan's ExportWorker design (awaitWork returns ExportWork with lock already released) directly addresses this.

### 3. R1 — Exporter Shutdown Without Timeout Confirmed

`PlatformDropOldestExportSpanProcessor.java:168`: `exporterShutdown = exporter.shutdown()` — **no timeout applied**.
Lines 176–182: `exporterShutdown.whenComplete(...)` → if `exporter.shutdown()` never completes, `shutdownResult` never completes, terminator thread leaks.
`02-concurrency-model.md` §Shutdown Thread Behavior: "If `exporter.shutdown()` hangs, terminator thread leaks and `shutdownResult` is never completed."

### 4. R3 — forceFlush/Shutdown Race Confirmed

`forceFlush()` (lines 113–128):
1. Reads `shutdownRequested.get()` at line 114 — if false, continues
2. Creates `CompletableResultCode result` at line 118
3. Acquires lock at line 119, adds to `pendingFlushes` at line 121, signals, releases lock

Race window: between step 1 (shutdownRequested read = false) and step 3 (pendingFlushes.add), shutdown() can be called and the worker can drain pendingFlushes (empty at that point) and exit. The promise added at step 3 will never be completed because the worker has exited.

`02-concurrency-model.md` §Pending forceFlush: "R3: if shutdown() is called concurrently between step 3 and the worker processing pendingFlushes..."

### 5. Single Factory Consumer for Builder

`PlatformExportProcessorFactory.java:96–99`: sole production consumer calls:
```
PlatformDropOldestExportSpanProcessor.builder(exporter)
    .readBspConfigFrom(config)
    .build()
```
No other production code calls `builder()`. This supports the plan's claim that replacing the Builder in PR-4 only requires updating one call site.

### 6. Lock Ownership Contained Within Class

All `queueLock` usage is confined to `PlatformDropOldestExportSpanProcessor.java`. No callers hold this lock from outside the class (confirmed: `queueLock` is `private final`). The decomposition into ExportBuffer (owning the lock) does not add cross-class lock leakage beyond current boundaries.

---

## Evidence FROM the Repository Supporting Only Low-Risk Extraction

### 1. DropOldestExportProcessorDefaults — Safe to Extract Immediately

`DropOldestExportProcessorDefaults.java` is already an isolated utility class (6 lines, delegating to `OtelSdkDefaults`). PR-1 wrapping this in `DropOldestExportProcessorConfig` is low-risk — it's purely a delegation refactor with no behavioral change.

### 2. OtelSdkDefaults — Package-Private Constants, No Behavioral Logic

`OtelSdkDefaults.java`: package-private final class, 10 constants. Safe to reference from new config class.

### 3. Counters Already Decoupled from Queue Operations

`droppedSpansOverflow`, `droppedSpansAfterShutdown`, `exportFailures`, `exportTimeouts` are `AtomicLong` fields incremented at isolated points. Moving them to `DropOldestProcessorMetrics` is a naming refactor without concurrency risk.

---

## Evidence Gaps (No Repository Support)

| Claim in Plan | Gap | Status |
|---------------|-----|--------|
| V6-clean-lite is the "target architecture" | No committed ADR exists. Plan §12 has a draft inline. No `ADR-drop-oldest-export-processor-v6-clean-lite.md` in `docs/decisions/`. | **Evidence missing** |
| ExportBuffer + ProcessorLifecycle coordination eliminates R3 | No implementation or pseudo-code spec for the interlock protocol in SHUTTING_DOWN state. | **Evidence missing** |
| "Bounded shutdown" is achievable without regression | No prototype or proof-of-concept. No validation of the timeout value or completion semantics for failed exports. | **Evidence missing** |
| JMX schema redesign from snapshot is non-breaking for operators | No operator JMX client inventory; ADR-jmx-wire-map-contract.md covers a different subsystem (Map-based wire protocol). The six export-counter JMX attributes have no formal wire contract ADR of their own. | **Evidence missing** |
| PR-0 characterization tests (M1–M10) will be achievable without production code changes | M4b (worker-loop death injection) requires white-box hook; plan acknowledges it may require production code changes. M3 (@Disabled known failure) is pre-accepted. | **Partially evidence-missing** |
| Performance: "no material regression" achievable with decomposition | JMH baseline exists (`QueueOfferBenchmark`, `docs/architecture/baselines/pr-0/`), but no post-decomposition performance data exists yet. | **Pre-condition only; no current evidence** |

---

## Validation Required Before Calling It "Target Architecture"

1. **ADR must be committed** — the plan's §12 draft ADR must be filed as `docs/decisions/ADR-drop-oldest-export-processor-v6-clean-lite.md` with status "Proposed" and reviewed by architecture committee.
2. **Bounded shutdown semantic change** must appear in the ADR as an explicit decision with options considered and rationale.
3. **Deterministic forceFlush** (fixing R3) must appear in the ADR as an explicit decision about post-SHUTTING_DOWN behavior.
4. **JMX schema change** must identify which authority (which ADR or architecture decision) permits changing the export-counter JMX attribute names.
5. **PR-0 results** (M1–M10 run results) must be documented before PR-1 begins.

---

## Recommended Wording

| Scenario | Wording |
|----------|---------|
| If ADR approved after Phase 0 | "V6-clean-lite is the approved target architecture (ADR-XXXX, Accepted), validated by PR-0 characterization test results." |
| Current state (no ADR, no PR-0) | "V6-clean-lite is the **candidate target architecture** for `PlatformDropOldestExportSpanProcessor`, subject to Phase 0–3 validation and ADR approval." |
| Conservative option if committee defers | "V6-clean-lite is the **architecture hypothesis** under evaluation; adoption decision pending PR-0 results and ADR approval." |

**Recommendation:** Use "candidate target architecture subject to Phase 0–3 validation" in all current plan documents. Upgrade to "target architecture" only after ADR is accepted and PR-0 results are documented.

---

## Final Recommendation

The decomposition rationale is evidence-based and the identified risks (R1–R4) are confirmed in source code. The plan should proceed, but the following corrections are required before Perplexity/external review:

1. Change "target architecture" → "candidate target architecture subject to ADR approval"
2. Commit ADR-v6-clean-lite.md with status "Proposed"
3. Fix rollback claim (see `00-refresh-executive-summary.md`, Critical Factual Error)
4. Specify ExportBuffer ↔ ProcessorLifecycle coordination protocol for SHUTTING_DOWN state in plan §7
