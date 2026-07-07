# Concurrency Model: PlatformDropOldestExportSpanProcessor

> Evidence-first. All line references are to `PlatformDropOldestExportSpanProcessor.java`.

---

## Threads Involved

| Thread | Name | Type | Created by |
|--------|------|------|------------|
| Producer threads | Application threads | Application | OTel SDK (span.end() callers) |
| Worker | `"platform-tracing-drop-oldest-exporter"` | Daemon | Constructor (line 68–70) |
| Terminator | `"platform-tracing-drop-oldest-shutdown"` | Daemon | `shutdown()` (line 148) |
| Exporter-callback | (depends on exporter impl) | Unknown | `exporter.shutdown().whenComplete()` (line 181) |

---

## All Shared Mutable State

| Field | Type | Protected by | Mutated by | Read by |
|-------|------|-------------|------------|---------|
| `queue` | `ArrayDeque<SpanData>` | `queueLock` | Producer (enqueue, evict), Worker (drain) | Worker (drain, shouldExportNow) |
| `pendingFlushes` | `List<CompletableResultCode>` | `queueLock` | Producer (forceFlush adds), Worker (drains) | Worker (shouldExportNow, drain) |
| `shutdownRequested` | `AtomicBoolean` | — (atomic) | Caller (shutdown), Terminator (no, read only) | Producer (onEnd fast path), Worker (shouldExportNow, drain exit condition) |
| `shutdownResult` | `CompletableResultCode` | — (atomic internals) | Terminator (succeed/fail) | Caller (returned from shutdown) |
| `workerTerminated` | `CountDownLatch` | — (CountDownLatch) | Worker (countDown in finally) | Terminator (await) |
| `droppedSpansOverflow` | `AtomicLong` | — (atomic) | Producer (enqueueWithDropOldest) | JMX consumer |
| `droppedSpansAfterShutdown` | `AtomicLong` | — (atomic) | Producer (onEnd fast path), Terminator (on timeout) | JMX consumer |
| `exportFailures` | `AtomicLong` | — (atomic) | Worker (exportBatch) | JMX consumer |
| `exportTimeouts` | `AtomicLong` | — (atomic) | Worker (exportBatch) | JMX consumer |
| `exportFailureLogged` | `AtomicBoolean` | — (atomic) | Worker (logExportFailureOnce) | Worker (logExportFailureOnce) |

---

## Lock and Condition Usage

### `queueLock` (ReentrantLock, non-fair)

**Acquired by:**
- `enqueueWithDropOldest` (producer threads)
- `workerLoop` inner wait loop
- `forceFlush` (to add promise + signal)
- `shutdown` (to signal only)
- `tryExport` (inner drain loop during shutdown)
- `queueSizeSafe` (getQueueSize getter)

**Condition:** `queueNotEmpty` — associated with `queueLock`.

### Signal events

| Signaled by | When |
|-------------|------|
| `enqueueWithDropOldest` | When `queue.size() >= maxExportBatchSize` OR `!pendingFlushes.isEmpty()` |
| `forceFlush` | Always, unconditionally |
| `shutdown` | Always, unconditionally |

`signalAll()` is used throughout (not `signal()`). This is correct: there is exactly one waiter (the worker), but it also ensures any stale waiting state is always cleared after structural changes. No risk of lost wakeup due to signal vs signalAll choice.

---

## Worker Loop State Machine

```
[WAIT]
   │ awaitNanos(remaining scheduleDelay)
   │ OR signaled by producer/forceFlush/shutdown
   ↓
[EVALUATE shouldExportNow?]
   ├─ NO  → re-enter awaitNanos (remaining time)
   └─ YES → drain batch + flush promises
              ↓
           [EXPORT outside lock]
              ↓
           [COMPLETE promises]
              ↓
           [CHECK shuttingDown && queue empty] → EXIT
              ↓ (not done)
           [LOOP back to WAIT]
```

**Key detail:** the lock is acquired for the `await` + drain, then **released before** `tryExport`. The exporter is always called outside the lock — this is what makes producer-side `onEnd` non-blocking even when the exporter is slow.

---

## Non-Standard Locking Pattern (Maintenance Hazard)

**Location:** Lines 240–270 in `workerLoop`.

```java
queueLock.lock();
try {
    long now = System.nanoTime();
    long elapsedSinceExport = now - lastExportNanos;
    while (!shouldExportNow(elapsedSinceExport)) {
        long waitNanos = ...;
        try {
            queueNotEmpty.awaitNanos(waitNanos);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            shuttingDown = true;
            batch = drainBatchLocked();
            flushPromises = drainPendingFlushesLocked();
            queueLock.unlock();   // ← EXPLICIT EARLY UNLOCK (line 254)
            tryExport(batch, flushPromises, true);
            return;               // ← returns from workerLoop
        }
        ...
    }
    ...
} finally {
    if (queueLock.isHeldByCurrentThread()) {  // ← guard against double-unlock
        queueLock.unlock();
    }
}
```

**Why this exists:** the `InterruptedException` path must release the lock before calling `tryExport` (which calls `exporter.export()`) — holding the lock during export would block all producers. But the `finally` block also unlocks. The `isHeldByCurrentThread()` guard prevents double-unlock.

**Risk:** any refactoring that restructures this try/catch/finally — e.g., extracting a method, reordering blocks, or converting to a different lock pattern — can silently:
- Re-introduce **double-unlock** (throws `IllegalMonitorStateException`), or
- Introduce **lock-leak** (deadlock for all producer threads)

This is the highest-risk single code region in the class.

---

## Shutdown Thread Behavior

The terminator thread runs independently of the caller:

```
Caller thread:  shutdown() → set flag → signal → start terminator → return shutdownResult
                              (non-blocking)

Terminator:     await workerTerminated(shutdownTimeout)
                  ├─ success: → exporter.shutdown() → whenComplete → shutdownResult
                  └─ timeout: → interrupt worker → drain remaining to droppedAfterShutdown
                               → exporter.shutdown() → whenComplete → shutdownResult
```

**Risk:** `exporter.shutdown()` has **no timeout**. If it hangs, the terminator thread leaks and `shutdownResult` is never completed. The OTel SDK's `SdkTracerProvider.shutdown()` waits for all processor `shutdown()` codes; an incomplete `shutdownResult` would cause the SDK to hang indefinitely.

---

## Pending forceFlush Coordination

When `forceFlush()` is called:
1. A new `CompletableResultCode` is added to `pendingFlushes` under `queueLock`.
2. `signalAll()` wakes worker.
3. Worker observes `!pendingFlushes.isEmpty()` → `shouldExportNow() = true`.
4. Worker drains batch AND `drainPendingFlushesLocked()` atomically under lock.
5. After `tryExport()`, calls `promise.succeed()` or `promise.fail()`.

**Concurrent multiple flushes:** multiple promises accumulate in `pendingFlushes`. All are drained and completed together in one worker cycle. This is correct but not explicitly tested.

**Race with shutdown (R3):** if `shutdown()` sets `shutdownRequested` and worker exits before seeing the pending flush, the promise is never completed. Scenario:
1. `forceFlush()` adds promise (step 1).
2. Worker drains batch (empty), completes flush promises (one cycle).
3. `shutdown()` called.
4. Worker exits.
5. Another `forceFlush()` adds promise AFTER worker exit → promise never completed.

---

## Interruption Behavior

**Interrupt source:** `workerThread.interrupt()` called by terminator thread on shutdown timeout (line 159).

**Worker response:** `InterruptedException` from `queueNotEmpty.awaitNanos()`:
1. Re-asserts interrupt flag: `Thread.currentThread().interrupt()`.
2. Sets `shuttingDown = true`.
3. Drains batch and pending flushes under lock.
4. **Explicit `queueLock.unlock()`** (the maintenance hazard described above).
5. Calls `tryExport(batch, flushPromises, true)` — drain remaining queue.
6. Returns from `workerLoop`.
7. `finally` block: `workerTerminated.countDown()`.

**Note:** interrupt during `exportBatch` (inside `exporter.export().join()`) is not handled. If the exporter blocks and the worker is interrupted while in `join()`, the interrupt is not propagated; `join()` with nanosecond timeout does not throw `InterruptedException` — it simply returns `isDone() = false`, triggering the timeout path.

---

## Known Risks Summary

| Risk | Type | Severity |
|------|------|----------|
| Explicit `queueLock.unlock()` + `isHeldByCurrentThread()` guard | Maintenance hazard (double-unlock) | High |
| `exporter.shutdown()` has no timeout | Resource leak (terminator thread, `shutdownResult`) | High |
| forceFlush promise may not complete if worker exits first | Race condition | Medium |
| Worker death from unchecked exception stops all exports | Unrecoverable failure | Medium |
| `logExportFailureOnce` permanent throttle | Observability blind spot | Low |
| `shutdownRequested` fast-path vs lock double-check race | Counter misattribution | Low |
| Multiple concurrent `forceFlush` not tested | Untested code path | Low |

---

## Whether Current Code Has Specific Hazards

| Hazard | Present? | Details |
|--------|----------|---------|
| Nested locking | No | Only one lock (`queueLock`) used in all paths |
| Double-unlock risk | **Yes** — structurally present, but guarded | See maintenance hazard section |
| Lost-signal risk | No | `signalAll()` used, condition is monotonic (shut down = no recovery needed) |
| Blocked exporter risk during producer | No | `exporter.export()` called outside `queueLock` |
| forceFlush completion risk | **Yes** — race with shutdown | See R3 |
| Worker spin risk | No | `awaitNanos` with bounded sleep, `MIN_WORKER_AWAIT_NANOS = 1ms` floor |
