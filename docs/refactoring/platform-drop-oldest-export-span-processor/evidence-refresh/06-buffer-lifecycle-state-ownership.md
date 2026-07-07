# 06 — Buffer/Lifecycle State Ownership

> Evidence refresh. Source of truth: `PlatformDropOldestExportSpanProcessor.java` (577 lines).
> Do NOT modify production code on the basis of this file.

---

## Complete Field Inventory

| Line | Field | Type | Initial value |
|------|-------|------|---------------|
| 31 | `MIN_WORKER_AWAIT_NANOS` | `static final long` | `TimeUnit.MILLISECONDS.toNanos(1)` |
| 33 | `exporter` | `final SpanExporter` | from Builder |
| 34 | `maxQueueSize` | `final int` | from Builder |
| 35 | `maxExportBatchSize` | `final int` | from Builder |
| 36 | `scheduleDelayNanos` | `final long` | `builder.scheduleDelay.toNanos()` |
| 37 | `exportTimeoutNanos` | `final long` | `builder.exportTimeout.toNanos()` |
| 38 | `shutdownTimeoutNanos` | `final long` | `builder.shutdownTimeout.toNanos()` |
| 40 | `queue` | `final ArrayDeque<SpanData>` | `new ArrayDeque<>(maxQueueSize)` |
| 41 | `queueLock` | `final ReentrantLock` | `new ReentrantLock()` (non-fair) |
| 42 | `queueNotEmpty` | `final Condition` | `queueLock.newCondition()` |
| 44 | `shutdownRequested` | `final AtomicBoolean` | `false` |
| 45 | `shutdownResult` | `final CompletableResultCode` | `new CompletableResultCode()` |
| 46 | `workerTerminated` | `final CountDownLatch` | `new CountDownLatch(1)` |
| 47 | `workerThread` | `final Thread` | daemon, started in constructor |
| 49 | `droppedSpansOverflow` | `final AtomicLong` | `0` |
| 50 | `droppedSpansAfterShutdown` | `final AtomicLong` | `0` |
| 51 | `exportFailures` | `final AtomicLong` | `0` |
| 52 | `exportTimeouts` | `final AtomicLong` | `0` |
| 54 | `exportFailureLogged` | `final AtomicBoolean` | `false` |
| 56 | `pendingFlushes` | `final List<CompletableResultCode>` | `new ArrayList<>()` |

**Critical structural fact:** `queue` (line 40) and `pendingFlushes` (line 56) are protected by the **same** `queueLock` (line 41). They are inseparable from a locking perspective. Any design that assigns them to different owners creates a dual-lock protocol that does not exist in the current code and would require a full concurrency redesign.

---

## State Ownership Table (V6-clean-lite Target)

| State | Proposed Owner | Must NOT own | Why | Test gate |
|-------|---------------|-------------|-----|-----------|
| `queue` (`ArrayDeque<SpanData>`) | `ExportBuffer` | `ProcessorLifecycle`, `ExportWorker` | Buffer is the only class that acquires `queueLock`; worker only receives drained `ExportWork` | M10 (drop-oldest invariants) |
| `pendingFlushes` (`List<CompletableResultCode>`) | `ExportBuffer` | `ProcessorLifecycle`, facade | Same lock as `queue` — must be co-owned; splitting ownership across classes reintroduces the dual-lock hazard | M1 (forceFlush+shutdown), M2 (concurrent forceFlush) |
| `queueLock` (`ReentrantLock`) | `ExportBuffer` | Any class outside `ExportBuffer` | Lock must not leak; `ExportWorker` and `ProcessorLifecycle` must never hold or release it | M6 (shutdown-timeout interrupt) |
| `queueNotEmpty` (`Condition`) | `ExportBuffer` | Any class outside `ExportBuffer` | Condition is bound to `queueLock`; signaling must only occur via `ExportBuffer` API (e.g. `accept()`, `requestFlush()`, `signalShutdown()`) | M2 |
| Lifecycle state (RUNNING/SHUTTING_DOWN/TERMINATED) | `ProcessorLifecycle` | `ExportBuffer`, `ExportWorker` | `ExportWorker` holds a nested execution-state enum only; it must not make processor-level lifecycle decisions | M1, M7 (forceFlush-after-shutdown) |
| `shutdownResult` (`CompletableResultCode`) | `ProcessorLifecycle` | `ExportBuffer`, `ExportWorker` | Exactly-once completion guarantee belongs to the lifecycle state machine; worker signals lifecycle, lifecycle completes the promise | M3 (shutdown-hang baseline), M6 |
| Worker thread ref (`workerThread`) | `ExportWorker` | `ProcessorLifecycle`, `ExportBuffer` | Thread lifecycle is ExportWorker's concern; interrupt from lifecycle must go through `ExportWorker.requestStop()` API, not direct `workerThread.interrupt()` | M6 |
| Worker exit flag (`workerTerminated` / equivalent) | `ExportWorker` | `ProcessorLifecycle` | Worker signals termination via a callback/latch to `ProcessorLifecycle`; lifecycle does not own the latch directly | M3, M6 |
| `exportFailures` counter | `DropOldestProcessorMetrics` | `ProcessorLifecycle` | Observability counters are not lifecycle state; `TimedSpanExporter` increments via metrics API | M4a (exporter exception isolation) |
| `exportTimeouts` counter | `DropOldestProcessorMetrics` | `ProcessorLifecycle` | Subcategory of `exportFailures`; both must be incremented atomically in `TimedSpanExporter` | M4a |
| `droppedSpansOverflow` counter | `DropOldestProcessorMetrics` (incremented from `ExportBuffer`) | `ExportWorker` | Overflow happens inside `ExportBuffer.accept()` under lock; counter increment must be co-located with eviction | M10 |
| `droppedAfterShutdown` counter | `DropOldestProcessorMetrics` (incremented from `ProcessorLifecycle`/facade) | `ExportBuffer` | After-shutdown drops occur before queue entry, at the `onEnd` fast path or in the lifecycle state check | M7 |
| `exportFailureLogged` / `logExportFailureOnce` flag | `DropOldestProcessorMetrics` or `TimedSpanExporter` (private) | `ProcessorLifecycle`, facade | Logging throttle is an observability concern; see File 07 for full treatment | M9 (log throttling) |

---

## Lock Ownership Protocol

### Who May Acquire `queueLock`

In the current monolith, `queueLock` is acquired by five distinct paths:
- `enqueueWithDropOldest` (producer threads)
- `workerLoop` inner wait loop
- `forceFlush` (to add promise + signal)
- `shutdown` (to signal only)
- `tryExport` drain loop during shutdown
- `queueSizeSafe` getter

**In V6-clean-lite**, only `ExportBuffer` may acquire `queueLock`. All callers interact with `ExportBuffer` via its public API:

| Caller | ExportBuffer API call | Lock behaviour |
|--------|----------------------|----------------|
| Producer (`onEnd`) | `ExportBuffer.accept(spanData)` | Lock acquired and released inside `accept()` |
| `forceFlush()` path | `ExportBuffer.requestFlush()` → returns `CompletableResultCode` | Lock acquired/released inside `requestFlush()` |
| `ProcessorLifecycle.shutdown()` | `ExportBuffer.signalShutdown()` | Lock acquired/released inside `signalShutdown()` |
| `ExportWorker` | `ExportBuffer.awaitWork()` | Lock acquired inside, **MUST be released before returning** |
| JMX / metrics | `ExportBuffer.queueSize()` | Lock acquired/released inside |

### What Is Forbidden

- `ProcessorLifecycle` MUST NOT hold `queueLock` at any point. If lifecycle calls `ExportBuffer.signalShutdown()`, that method acquires and releases the lock internally.
- `ExportWorker` MUST NOT call `queueLock.unlock()` in a `catch` block. This is the exact R2-hazard in the current code (lines 231, 245–247). `awaitWork()` must encapsulate the entire lock acquire/await/drain/release cycle.
- No class other than `ExportBuffer` may call `queueLock.lock()`, `queueLock.unlock()`, or `queueNotEmpty.signalAll()`.

### Forbidden Pattern: "ProcessorLifecycle holds queueLock"

```
// REJECTED — ProcessorLifecycle must NOT do this:
queueLock.lock();          // lifecycle acquires buffer's lock
try {
    lifecycle.transitionTo(SHUTTING_DOWN);
    queueNotEmpty.signalAll();
} finally {
    queueLock.unlock();
}
```

This creates a hidden coupling where `ProcessorLifecycle` becomes a second coordinator over `ExportBuffer` state. The correct design:

```
// CORRECT — lifecycle delegates signal to ExportBuffer:
lifecycle.transitionTo(SHUTTING_DOWN);
exportBuffer.signalShutdown();   // ExportBuffer acquires/releases its own lock
```

### Forbidden Pattern: "ExportWorker calls lock.unlock() in catch"

```
// REJECTED — reproduces the current R2-hazard:
queueLock.lock();
try {
    while (!shouldExportNow()) {
        try {
            queueNotEmpty.awaitNanos(waitNanos);
        } catch (InterruptedException ie) {
            queueLock.unlock();          // explicit early unlock — maintenance hazard
            tryExport(batch, promises);
            return;
        }
    }
    batch = drain();
} finally {
    if (queueLock.isHeldByCurrentThread()) {   // guard needed only because of the hazard above
        queueLock.unlock();
    }
}
```

The replacement contract is that `ExportBuffer.awaitWork()` returns an already-drained, already-unlocked `ExportWork` value object. The worker never touches the lock.

---

## ExportBuffer.awaitWork() Contract

`ExportBuffer.awaitWork()` MUST satisfy all of the following:

1. **Lock acquired at entry.** The method acquires `queueLock` before entering the await loop.
2. **Lock released before return.** The method MUST release `queueLock` before returning `ExportWork` to the caller. The returned value is a plain immutable record; no lock is embedded in it.
3. **Drain is atomic under lock.** `queue` drain and `pendingFlushes` drain happen in the same critical section.
4. **Interrupt is handled internally.** If `queueNotEmpty.awaitNanos()` throws `InterruptedException`, the method handles it internally (drains what it can, marks the work as terminal), releases the lock, and returns. The caller (`ExportWorker`) sees a normal `ExportWork` return, not an exception propagated with a live lock.
5. **No CompletableResultCode left unreachable.** All `FlushRequest` promises drained in `awaitWork()` must be included in the returned `ExportWork`. The worker completes them after export.
6. **Shutdown signal is visible.** `ExportWork` carries a `isShuttingDown` flag; the worker uses this to determine drain-loop continuation. It does not re-check processor lifecycle state directly.

---

## Preventing ProcessorLifecycle from Becoming a Hidden Second Coordinator

The principal risk in the V6-clean-lite design is that `ProcessorLifecycle`, by owning the state machine, accumulates secondary responsibilities over `ExportBuffer` state — effectively becoming a second coordinator.

Guardrails:

| Guardrail | Enforcement |
|-----------|-------------|
| `ProcessorLifecycle` has zero direct references to `queueLock`, `queueNotEmpty`, `queue`, or `pendingFlushes` | Code review gate: no field of type `ReentrantLock`, `Condition`, `ArrayDeque`, or `List<CompletableResultCode>` in `ProcessorLifecycle` |
| `ProcessorLifecycle.shutdown()` calls `ExportBuffer.signalShutdown()` — not the reverse | Architectural review: dependency arrow ProcessorLifecycle → ExportBuffer (read-only signal); ExportBuffer does NOT reference ProcessorLifecycle |
| Worker exit is reported to `ProcessorLifecycle` via a callback, not by `ProcessorLifecycle` polling buffer state | `ExportWorker` calls `onWorkerTerminated(boolean drainedClean)` on `ProcessorLifecycle` in its `finally` block |
| `ProcessorLifecycle` does NOT complete `flushPromises` directly | `ExportWorker` is responsible for completing promises returned in `ExportWork`; lifecycle only completes `shutdownResult` |
| All state transitions in `ProcessorLifecycle` (RUNNING → SHUTTING_DOWN → TERMINATED) are driven by `CAS` on an `AtomicReference<State>`, not by reading buffer internals | Unit test: transition tests use mock `ExportBuffer` that records calls |

**Anti-pattern to reject:** any design where `ProcessorLifecycle.shutdown()` directly iterates `pendingFlushes`, counts `queue.size()`, or decides whether to signal the condition. These are `ExportBuffer`'s responsibilities.
