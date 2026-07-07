# Responsibility Decomposition: PlatformDropOldestExportSpanProcessor

> Decomposition is analysis only. No refactoring is recommended yet.
> Candidate components are described but not endorsed — see risk levels.

---

## Current Responsibilities Inside the Class

| # | Responsibility | Methods / Fields | Lines |
|---|---------------|-----------------|-------|
| R1 | **Bounded drop-oldest queue** | `queue`, `queueLock`, `queueNotEmpty`, `enqueueWithDropOldest()`, `drainBatchLocked()` | 41–43, 205–227, 301–312 |
| R2 | **Export worker lifecycle** | `workerThread`, `workerLoop()`, `shouldExportNow()` | 48, 68–71, 233–285, 288–299 |
| R3 | **Timed batch export** | `exportBatch()`, `exportTimeoutNanos` | 36, 370–393 |
| R4 | **forceFlush coordination** | `pendingFlushes`, `forceFlush()`, `drainPendingFlushesLocked()` | 57, 112–129, 315–322 |
| R5 | **Shutdown coordination** | `shutdownRequested`, `shutdownResult`, `workerTerminated`, `shutdown()`, terminator thread logic | 45–47, 132–192 |
| R6 | **Builder + config validation** | `Builder` inner class, `applyValidationWithSafeFallback()`, `readBspConfigFrom()` | 459–574 |
| R7 | **BSP config key reading** | `readBspConfigFrom()` only | 501–525 |
| R8 | **Observability counters** | `droppedSpansOverflow`, `droppedSpansAfterShutdown`, `exportFailures`, `exportTimeouts`, `exportFailureLogged`, all getters | 50–55, 415–444 |
| R9 | **Lifecycle state guard** | `shutdownRequested` (fast-path check in `onEnd`, `forceFlush`) | 45, 90–91, 113 |

---

## Candidate Extracted Components

### C1: BoundedDropOldestQueue

**Proposed responsibility:** Encapsulate the `ArrayDeque`, `queueLock`, and `queueNotEmpty` condition. Provide `enqueue(SpanData)` (with drop-oldest), `drainBatch(int maxSize)`, `size()`, `capacity()`, and `isEmpty()` operations. Expose `signalFlush()` for external wakers.

**Current methods/fields it would own:**
- `queue`, `queueLock`, `queueNotEmpty`, `maxQueueSize`
- `enqueueWithDropOldest()`, `drainBatchLocked()`, `queueSizeSafe()`
- `droppedSpansOverflow` counter (naturally owned here)

**Dependency direction:** C1 depends on nothing. All other components depend on C1.

**Behavior preservation notes:**
- The double-check of `shutdownRequested` inside `enqueueWithDropOldest` must stay or be migrated to a queue-listener callback. The queue itself should not own the shutdown state.
- `droppedSpansAfterShutdown` incremented inside the enqueue lock — must remain atomic with the lock acquisition.

**Risk level:** **Medium**. The lock pattern is complex (see `02-concurrency-model.md`). The interaction between `enqueueWithDropOldest` and `shutdownRequested` is subtle — if C1 does not know about shutdown, the shutdown double-check must be passed as a parameter or predicate.

---

### C2: ExportWorker

**Proposed responsibility:** Manage the single daemon thread, its start, the `workerLoop`, `shouldExportNow`, and the `MIN_WORKER_AWAIT_NANOS` minimum sleep.

**Current methods/fields it would own:**
- `workerThread`, `scheduleDelayNanos`, `MIN_WORKER_AWAIT_NANOS`
- `workerLoop()`, `shouldExportNow()`
- `workerTerminated` (latch to signal termination)

**Dependency direction:** C2 depends on C1 (queue drain), C3 (timed export), C4 (flush coordinator), C5 (shutdown state read).

**Behavior preservation notes:**
- The non-standard `InterruptedException` handler with explicit `queueLock.unlock()` is tightly coupled to C1's lock. Extracting C2 without C1 does not eliminate the hazard — it just moves it across class boundaries. The two components must be co-designed.
- `workerTerminated.countDown()` in the `finally` block must remain in C2's ownership.

**Risk level:** **High**. The `InterruptedException` path in `workerLoop` accesses C1's lock directly. Extraction requires a clean interface contract between C1 and C2 that handles the early-unlock path correctly.

---

### C3: TimedExporter

**Proposed responsibility:** Wrap `SpanExporter` with a bounded-time export: `exportWithTimeout(List<SpanData> batch, long timeoutNanos)` returning `boolean success`.

**Current methods/fields it would own:**
- `exporter`, `exportTimeoutNanos`
- `exportBatch()`
- `exportFailures`, `exportTimeouts`, `exportFailureLogged` counters

**Dependency direction:** C3 depends on `SpanExporter` (external). No dependency on queue or worker.

**Behavior preservation notes:**
- `logExportFailureOnce` throttle is owned here. Its permanent one-shot nature must be preserved.
- The counter semantics (`exportTimeouts` ⊆ `exportFailures`) must be maintained.
- `exporter.shutdown()` is called by C5 (shutdown coordinator), not C3. C3 only handles `export()`.

**Risk level:** **Low**. This is the cleanest extraction candidate — no lock interaction, pure input/output. The main constraint is preserving the counter types (public getters consumed by JMX).

---

### C4: ForceFlushCoordinator

**Proposed responsibility:** Manage `pendingFlushes` list (under queue lock) and promise completion after export.

**Current methods/fields it would own:**
- `pendingFlushes`
- `drainPendingFlushesLocked()` (under external lock)
- The `forceFlush()` method's promise-creation and signaling

**Dependency direction:** C4 depends on C1 (for lock and signal access), C5 (shutdown state read).

**Behavior preservation notes:**
- The `forceFlush-after-shutdown → ofSuccess()` short-circuit must be preserved.
- The list access is always under `queueLock` (C1's lock). C4 cannot own its own lock without restructuring the invariant that `pendingFlushes` and `queue` are atomically visible together.

**Risk level:** **Medium**. `pendingFlushes` is tightly coupled to `queueLock`. Extracting C4 without also extracting C1 means C4 borrows C1's lock, which is an awkward ownership model.

---

### C5: ShutdownCoordinator

**Proposed responsibility:** Manage `shutdownRequested`, `shutdownResult`, the terminator thread, and `exporter.shutdown()` delegation.

**Current methods/fields it would own:**
- `shutdownRequested`, `shutdownResult`, `workerTerminated`, `shutdownTimeoutNanos`
- `shutdown()`, terminator thread anonymous lambda (lines 148–188)
- `droppedSpansAfterShutdown` increment on timeout (line 164)

**Dependency direction:** C5 depends on C1 (signal + drain-under-lock on timeout), C2 (worker thread reference for interrupt), `SpanExporter` (for `exporter.shutdown()`).

**Behavior preservation notes:**
- `shutdown()` idempotency (cached `shutdownResult`) must be preserved.
- The `droppedSpansAfterShutdown.addAndGet(queue.size()) + queue.clear()` under lock on timeout must remain atomic with C1's lock.
- `exporter.shutdown()` hang is the primary unresolved risk. A refactored C5 could add a bounded timeout here (this would be a behavior improvement, not preservation).

**Risk level:** **High**. The terminator thread directly interacts with C1's lock and C2's thread reference. This is a three-way coupling point.

---

### C6: ProcessorConfigAndBuilder

**Proposed responsibility:** Encapsulate all builder state, validation logic, and `readBspConfigFrom()` key parsing. Produce an immutable `ProcessorConfig` record passed to the processor constructor.

**Current methods/fields it would own:**
- `Builder` inner class (entire)
- `applyValidationWithSafeFallback()`
- `readBspConfigFrom()`

**Dependency direction:** C6 depends on `DropOldestExportProcessorDefaults`, `ExtensionDefaults`, `OTel ConfigProperties`. No runtime dependency.

**Behavior preservation notes:**
- The public `Builder` is part of the external API (used by `PlatformExportProcessorFactory`). Its method names and parameter types cannot change.
- The production-safe WARN + fallback policy (no fail-fast) must be preserved for all parameters except `null` exporter.

**Risk level:** **Low**. Builder extraction is purely structural. The only constraint is the public `Builder` API surface.

---

### C7: ObservabilitySnapshot

**Proposed responsibility:** Provide a consistent snapshot of all six counters. Could be a record: `ObservabilitySnapshot(droppedOverflow, droppedAfterShutdown, exportFailures, exportTimeouts, queueCapacity, queueSize)`.

**Current methods/fields it would own:**
- `getDroppedSpansOverflow()`, `getDroppedSpansAfterShutdown()`, `getExportFailures()`, `getExportTimeouts()`, `getQueueCapacity()`, `getQueueSize()`

**Dependency direction:** C7 depends on C1 (queue size), C3 (export counters), C5 (shutdown counters). Read-only access.

**Behavior preservation notes:**
- `getQueueSize()` acquires `queueLock`. Must remain thread-safe.
- All counters are `AtomicLong` — reads are inherently consistent per-counter but NOT a consistent cross-counter snapshot. The current API does NOT provide a consistent snapshot (each getter is independent). This is an existing characteristic, not a bug to fix during refactoring.

**Risk level:** **Low**. The six getter signatures are the constraint; the internal representation can change freely.

---

### C8: LifecycleStateGuard

**Proposed responsibility:** Centralize `shutdownRequested` state and the `isShutdown()` check used in `onEnd`, `forceFlush`, and `enqueueWithDropOldest`.

**Current methods/fields it would own:**
- `shutdownRequested` (AtomicBoolean)
- Fast-path read in `onEnd` (line 90) and `forceFlush` (line 113)

**Dependency direction:** Used by C4 (forceFlush fast path), C5 (set on shutdown), C1 (double-check under lock), `onEnd` (fast path).

**Behavior preservation notes:**
- The fast-path (unsynchronized) read + lock-guarded double-check pattern is intentional for performance. A guard object must preserve this two-level check.

**Risk level:** **Low**. Simple boolean state. The main risk is accidentally synchronizing the fast path.

---

## Candidate Component Dependency Graph

```
External:
  SpanExporter ← C3 (export), C5 (shutdown)
  OTel ConfigProperties ← C6 (config reading)
  PlatformExportProcessorFactory ← C6 (builder API)
  PlatformExportControl ← C7 (getter API)

Internal:
  C6 (Config/Builder) → produces config → C1, C2, C3, C5
  C1 (Queue) ← C2 (Worker drains), C5 (Terminator drains on timeout)
  C2 (Worker) → C3 (exports batch), C4 (completes flushes)
  C4 (Flush) → C1 (drainPendingFlushesLocked under C1 lock)
  C5 (Shutdown) → C1 (signal + timeout drain), C2 (interrupt)
  C7 (Observability) reads C1, C3, C5 counters
  C8 (LifecycleGuard) → read by C1, C4, onEnd
```

---

## Summary Table

| Component | Complexity | Extraction Risk | Value |
|-----------|-----------|----------------|-------|
| C1: BoundedDropOldestQueue | High (lock + condition + eviction) | Medium | High |
| C2: ExportWorker | Very high (non-standard unlock pattern) | High | High |
| C3: TimedExporter | Low | Low | Medium |
| C4: ForceFlushCoordinator | Medium | Medium | Low |
| C5: ShutdownCoordinator | High (3-way coupling) | High | Medium |
| C6: ProcessorConfigAndBuilder | Low | Low | Medium |
| C7: ObservabilitySnapshot | Low | Low | Low |
| C8: LifecycleStateGuard | Very low | Low | Low |

**Recommended extraction order (if refactoring proceeds):**
1. C6 (Config/Builder) — zero runtime risk, immediate testability win
2. C3 (TimedExporter) — self-contained, independent tests possible
3. C7 (ObservabilitySnapshot) — structural only
4. C8 (LifecycleGuard) — trivial, reduces duplication
5. C1 + C2 together (must be co-designed due to lock coupling) — highest value, highest risk
6. C4 + C5 last — require C1+C2 to be stable first
