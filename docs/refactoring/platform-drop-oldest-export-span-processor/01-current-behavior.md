# Current Behavior: PlatformDropOldestExportSpanProcessor

> Evidence-first. All line references are to
> `platform-tracing-otel-extension/src/main/java/space/br1440/platform/tracing/otel/extension/processor/PlatformDropOldestExportSpanProcessor.java`
> unless stated otherwise.

---

## Lifecycle Overview

```
[Constructor]
   ↓ starts daemon thread "platform-tracing-drop-oldest-exporter"
[workerLoop running]
   ↓
[onEnd × N] → enqueueWithDropOldest (producer threads, under queueLock)
   ↓
[Worker wakes] → drainBatchLocked + drainPendingFlushesLocked → tryExport
   ↓
[forceFlush] → adds promise to pendingFlushes, signals worker
[shutdown]   → sets shutdownRequested, signals worker, starts terminator thread
   ↓
[Terminator thread] → awaits workerTerminated, calls exporter.shutdown()
```

---

## Constructor

**Location:** Lines 59–71

- Accepts a `Builder` — all immutable parameters are copied from builder fields.
- Initializes `ArrayDeque<SpanData>` with capacity `maxQueueSize`.
- Creates and **immediately starts** a daemon thread named `"platform-tracing-drop-oldest-exporter"`.
- No public constructor. Factory pattern only: `PlatformDropOldestExportSpanProcessor.builder(exporter).build()`.

**Evidence of immediate start:** line 70, `this.workerThread.start()`.

---

## onStart

**Location:** Lines 73–77

- **No-op body.** The method must be overridden because `SpanProcessor.onStart` is abstract in OTel SDK (confirmed via compilation error when removed).
- `isStartRequired()` returns `false` (line 81), so the OTel SDK's `SdkTracerProvider` will not invoke `onStart` at runtime.
- `PlatformCompositeSpanProcessor.onStart()` also checks `isStartRequired()` before dispatching (line 69 of `PlatformCompositeSpanProcessor.java`).

**Net effect:** `onStart` is dead code at runtime. The processor participates in the pipeline at the `onEnd` stage only.

---

## onEnd

**Location:** Lines 84–104

**Guards (fast path, no lock):**
1. `!span.getSpanContext().isSampled()` → return immediately (unsampled spans never enter the queue).
2. `shutdownRequested.get()` → `droppedSpansAfterShutdown.incrementAndGet()` + return.

**Snapshot materialization:**
- `span.toSpanData()` called in producer thread. Full `SpanData` immutable snapshot. `RuntimeException` caught → `logExportFailureOnce` + return (span is lost, no counter increment for this case).

**Enqueue:**
- Delegates to `enqueueWithDropOldest(snapshot)`.

---

## Queue Behavior

**Location:** `enqueueWithDropOldest`, lines 205–227.

**Under `queueLock`:**
1. Double-check `shutdownRequested` (concurrent shutdown between fast path and lock acquisition): if true → `droppedSpansAfterShutdown.incrementAndGet()` + return.
2. If `queue.size() >= maxQueueSize`: `queue.pollFirst()` (evict oldest) → `droppedSpansOverflow.incrementAndGet()`.
3. `queue.offerLast(snapshot)` (always succeeds: space guaranteed by step 2 or was available).
4. Signal condition if `queue.size() >= maxExportBatchSize || !pendingFlushes.isEmpty()`.

**Key property:** Producer thread holds the lock only for the eviction + offer + conditional signal. `exporter.export()` is always called outside the lock (by the worker).

**Queue type:** `ArrayDeque<SpanData>` — no concurrent access; all access guarded by `queueLock`.

---

## Export Behavior

### Schedule conditions (shouldExportNow)

**Location:** Lines 288–299. Evaluated under `queueLock`.

| Condition | Trigger |
|-----------|---------|
| `shutdownRequested` | Immediately |
| `!pendingFlushes.isEmpty()` | Immediately |
| `queue.size() >= maxExportBatchSize` | Immediately |
| `!queue.isEmpty() && elapsed >= scheduleDelay` | After schedule delay |

### drainBatchLocked

- Removes `min(queue.size(), maxExportBatchSize)` spans from the head of the queue.
- Returns empty list if queue is empty.
- Called under `queueLock`.

### tryExport (lines 331–364)

1. If batch is empty: `success = true` (no-op, still completes flush promises).
2. Calls `exportBatch(batch)`.
3. If `shuttingDown`: loops draining remaining spans in batches until queue is empty.
4. Completes all `flushPromises` with `success` or `failure`.

### exportBatch (lines 370–393)

1. `exporter.export(batch)` — `RuntimeException` caught → `exportFailures.incrementAndGet()` + `logExportFailureOnce` → returns `false`.
2. `resultCode.join(exportTimeoutNanos, TimeUnit.NANOSECONDS)` — waits for exporter to complete.
3. If not done: `exportTimeouts.incrementAndGet()` + `exportFailures.incrementAndGet()` + `logExportFailureOnce` → returns `false`.
4. If `!resultCode.isSuccess()`: `exportFailures.incrementAndGet()` + `logExportFailureOnce` → returns `false`.
5. Returns `true` on success.

**Note:** `logExportFailureOnce` uses `AtomicBoolean exportFailureLogged`. Only the **first** failure produces a WARN log. All subsequent failures are silently counted. The boolean is never reset.

---

## forceFlush Behavior

**Location:** Lines 112–129.

1. If `shutdownRequested.get()`: returns `CompletableResultCode.ofSuccess()` immediately. **No drain.** (Documented in ADR.)
2. Creates new `CompletableResultCode` result.
3. Under `queueLock`: adds result to `pendingFlushes`, calls `queueNotEmpty.signalAll()`.
4. Returns result to caller immediately.

**Promise completion:** the worker loop, in `tryExport`, calls `promise.succeed()` or `promise.fail()` after the export attempt completes. The caller must `join()` the returned code to wait.

**Risk (R3):** if `shutdown()` is called concurrently between step 3 and the worker processing `pendingFlushes`, the worker may exit its loop before processing the promise, leaving it permanently incomplete.

---

## Shutdown Behavior

**Location:** Lines 132–192.

### Phase 1: Signal (caller thread)

1. `shutdownRequested.compareAndSet(false, true)` — if already `true`, returns cached `shutdownResult` (idempotent, no re-drain).
2. Under `queueLock`: `queueNotEmpty.signalAll()` to wake worker.
3. Returns `shutdownResult` immediately (non-blocking to caller).

### Phase 2: Terminator daemon thread ("platform-tracing-drop-oldest-shutdown")

Started by caller thread immediately after signaling.

1. `workerTerminated.await(shutdownTimeoutNanos, NANOSECONDS)` — blocks until worker exits or timeout.
2. **If timeout:**
   - Logs WARN.
   - `workerThread.interrupt()`.
   - Under `queueLock`: `droppedSpansAfterShutdown.addAndGet(queue.size())` + `queue.clear()`.
3. Calls `exporter.shutdown()` — **no timeout applied here**.
4. `exporterShutdown.whenComplete(...)` → on success: `shutdownResult.succeed()`; on failure: `shutdownResult.fail()`.

**Critical:** if `exporter.shutdown()` never completes, `shutdownResult` never completes and the terminator thread remains alive indefinitely.

### Worker behavior during shutdown

- When `shutdownRequested.get()` is observed in `shouldExportNow`: `shuttingDown = true`.
- `tryExport` drains remaining queue in a loop until empty.
- Worker loop returns after drain: `finally { workerTerminated.countDown() }`.

---

## Observability Counters and Getters

**All counters initialized to 0; never reset.**

| Counter | Type | What it counts | Method |
|---------|------|----------------|--------|
| `droppedSpansOverflow` | `AtomicLong` | Spans evicted (drop-oldest) due to queue full | `getDroppedSpansOverflow()` |
| `droppedSpansAfterShutdown` | `AtomicLong` | Spans arriving after `shutdown()` call | `getDroppedSpansAfterShutdown()` |
| `exportFailures` | `AtomicLong` | Failed `exporter.export()` (any reason: throw/failure/timeout) | `getExportFailures()` |
| `exportTimeouts` | `AtomicLong` | Subset of exportFailures: timeout specifically | `getExportTimeouts()` |
| `queueCapacity` | `int` (final) | Configured `maxQueueSize` (immutable) | `getQueueCapacity()` |
| `queueSize` | derived | Current queue size (reads under `queueLock`) | `getQueueSize()` |

**Consumer:** `PlatformExportControl` (JMX MBean) accesses all six getters.
**Taxonomy reference:** `docs/tracing/dropped-span-reasons.md` maps counters to span loss categories.

---

## Builder and Configuration Behavior

**Location:** Lines 459–574. Inner class `Builder`.

### Configuration keys (via `readBspConfigFrom`)

| OTel BSP key | Field | Type |
|--------------|-------|------|
| `otel.bsp.max.queue.size` | `maxQueueSize` | `int` |
| `otel.bsp.max.export.batch.size` | `maxExportBatchSize` | `int` |
| `otel.bsp.schedule.delay` | `scheduleDelay` | `Duration` |
| `otel.bsp.export.timeout` | `exportTimeout` | `Duration` |

`shutdownTimeout` is NOT read from BSP config. It uses `ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT = 10s`.

### Default values (from OtelSdkDefaults, via DropOldestExportProcessorDefaults)

| Parameter | Default |
|-----------|---------|
| `maxQueueSize` | 2048 |
| `maxExportBatchSize` | 512 |
| `scheduleDelay` | 5000 ms |
| `exportTimeout` | 5000 ms |
| `shutdownTimeout` | 10000 ms (ExtensionDefaults) |

### Validation (applyValidationWithSafeFallback, lines 536–573)

**Production-safe: WARN + fallback, NOT fail-fast.** An agent extension must not crash the JVM due to config typo.

| Rule | Fallback |
|------|----------|
| `maxQueueSize <= 0` | `defaultMaxQueueSize()` = 2048 |
| `maxExportBatchSize <= 0` | `defaultMaxExportBatchSize()` = 512 |
| `maxExportBatchSize > maxQueueSize` | clamped to `maxQueueSize` |
| `scheduleDelay == null || <= 0` | `defaultScheduleDelay()` = 5s |
| `exportTimeout == null || <= 0` | `defaultExportTimeout()` = 5s |
| `shutdownTimeout == null || <= 0` | `DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` = 10s |
| `exporter == null` | **Fail-fast** `NullPointerException` (only case) |

---

## Behavior That Must Not Change During Refactoring

| Invariant | Source |
|-----------|--------|
| Drop-oldest semantics: `pollFirst()` before `offerLast()` under lock | Queue contract, `PlatformDropOldestExportSpanProcessorOverflowPolicyTest` |
| `onEnd` never blocks the caller (producer thread) | SP-05 / Test 2 |
| `droppedSpansOverflow + exported == TOTAL` (no silent loss) | `PlatformDropOldestExportSpanProcessorOverflowPolicyTest.dropOldestPreservesNewestSpans` |
| `shutdown()` is idempotent: second call returns same `CompletableResultCode` | `PlatformDropOldestExportSpanProcessorLifecycleTest.shutdownIsIdempotent` |
| `forceFlush()` after `shutdown()` returns `ofSuccess()` immediately | Line 114, ADR §forceFlush |
| All six observability getter signatures (public API consumed by JMX) | `PlatformExportControl` |
| Builder `null` exporter is the only `NullPointerException` case | Builder contract, `PlatformDropOldestExportSpanProcessorBuilderValidationTest` |
| `otel.bsp.*` config keys must be read in `readBspConfigFrom()` | `SharedDefaultsAlignmentTest`, ADR |
| Worker is a daemon thread (JVM does not wait for it on normal exit) | Line 69 |
| `shutdownTimeout = 10s` from `ExtensionDefaults` (not from BSP config) | `ExtensionDefaults.java:49` |
