# Test Coverage and Gaps: PlatformDropOldestExportSpanProcessor

> Existing tests are described based on direct reading of test source.
> Missing tests are derived from code analysis — no test was invented.

---

## Existing Tests Grouped by Behavior

### Group 1: Builder Validation

**File:** `PlatformDropOldestExportSpanProcessorBuilderValidationTest.java`

| Test | Behavior Verified |
|------|-------------------|
| `nullExporterIsRejectedAtBuildTime` | `builder(null)` throws `NullPointerException` with message "exporter" |
| `zeroQueueSizeFallsBackToDefault` | `maxQueueSize(0)` → fallback to 2048, processor builds without throwing |
| `batchSizeGreaterThanQueueClampsToQueue` | `maxExportBatchSize(64).maxQueueSize(16)` → clamped to 16 |
| `negativeDurationsFallBackToDefaults` | Negative `scheduleDelay`, `exportTimeout`, `shutdownTimeout` → all replaced with defaults |
| `smallValidQueueSizeIsAccepted` | `maxQueueSize(4)` with `maxExportBatchSize(2)` → accepted without substitution |

**Coverage:** Builder validation is well-covered. Only missing case: `maxExportBatchSize(0)` explicit zero (separate from `> maxQueueSize`).

---

### Group 2: Drop-Oldest Queue Contract

**Files:** `PlatformDropOldestExportSpanProcessorOverflowPolicyTest.java`, `PlatformDropOldestExportSpanProcessorQueueCharacterizationTest.java`

| Test | Behavior Verified |
|------|-------------------|
| `dropOldestPreservesNewestSpans` | 34 spans, queue=4, batch=2; first batch exported before overflow; seq 2–5 absent (dropped oldest), last seq present; `dropped + exported == TOTAL` |
| `queueSizeReflectsState` | `getQueueSize() <= getQueueCapacity()` invariant under overflow pressure |
| SP-05/T1: `dropsOldestPendingSpanWhenQueueIsFull` | Identity proof: span-1 evicted, span-0 and span-2 exported |
| SP-05/T2: `onEndDoesNotBlockWhenQueueIsFull` | `onEnd` returns within 2s even when exporter blocked and queue full |
| SP-05/T3: `incrementsDroppedSpanCounterWhenQueueIsFull` | `droppedSpansOverflow == 2` after 2 evictions, deterministic (exporter blocked) |
| SP-05/T4: `exportsAcceptedSpansAfterQueuePressureIsReleased` | Post-pressure export: survivors (queued-1, queued-2) present; victim (queued-0) absent |
| SP-05/T5: `shutdownCompletesAfterQueuePressureScenario` | Shutdown returns non-null result; `droppedSpansOverflow == 1` preserved post-shutdown |

**Coverage:** Drop-oldest contract is well-covered. The counter balance (`dropped + exported == TOTAL`) provides the strongest behavioral invariant.

---

### Group 3: Lifecycle (forceFlush, shutdown, counters)

**File:** `PlatformDropOldestExportSpanProcessorLifecycleTest.java`

| Test | Behavior Verified |
|------|-------------------|
| `forceFlushDrainsQueueAndCompletesSuccessfully` | 7 spans, long scheduleDelay; `forceFlush()` drains all 7; `isSuccess()=true`; `queueSize=0` |
| `shutdownIsIdempotent` | Two calls to `shutdown()` return same `CompletableResultCode` instance |
| `onEndAfterShutdownIncrementsAfterShutdownCounter` | 3 spans after `shutdown()`: `droppedAfterShutdown >= 3`, `droppedOverflow == 0` |
| `exportTimeoutIncrementsCounter` | Never-completing exporter + short timeout: `flush.isSuccess()=false`, `exportTimeouts > 0`, `exportFailures >= exportTimeouts` |
| `exporterExceptionIsolatedAndCounted` | First export throws: `exportFailures >= 1`; worker continues; second call succeeds |
| `shutdownDrainsQueue` | 15 spans, long scheduleDelay; after `tp.close()`, all 15 exported within deadline; `droppedAfterShutdown == 0` |

**Coverage:** Core lifecycle behavior is covered. Several edge cases are missing (see gaps below).

---

### Group 4: BSP Config and Defaults Alignment

**Files:** `DropOldestExportProcessorDefaultsTest.java`, `SharedDefaultsAlignmentTest.java`

| Test | Behavior Verified |
|------|-------------------|
| `DropOldestExportProcessorDefaultsTest` (not read) | Default value assertions |
| `queueDefaults_aligned_with_agentExtension` | `TracingProperties.Queue` defaults match `DropOldestExportProcessorDefaults` |
| `limitsDefaults_matchDocumentedPlatformDefaults` | Span limits alignment |

---

### Group 5: No Double Export (SDK Integration)

**File:** `BspDropOldestNoDoubleExportTest.java`

| Test | Behavior Verified |
|------|-------------------|
| `everySpanExportedExactlyOnceUnderOptIn` | 50 spans via `AutoConfiguredOpenTelemetrySdk`; each `spanId` appears exactly once; `exportCalls == 50` |

This test verifies that replacing the stock BSP does not cause duplicate exports through both the old BSP and the new processor.

---

### Group 6: Autoconfigure Integration

**File:** `PlatformAutoConfigurationCustomizerExportProcessorTest.java`

| Behavior Verified |
|------|
| Default policy (UPSTREAM): processor is NOT activated |
| DROP_OLDEST opt-in: processor IS activated |
| Multi-exporter: fallback to stock BSP |
| Non-BSP processor: passthrough |

---

### Group 7: E2E Smoke (referenced, not read in full)

**File:** `BspDropOldestSafetyAgentSmokeTest.java` — confirms opt-in works in a full agent deployment with unreachable OTLP endpoint.

---

### Group 8: JMH Benchmarks

**File:** `QueueOfferBenchmark.java` — measures `onEnd` throughput in steady and saturated modes at 4-thread contention.

---

## Missing Tests Required Before Refactoring

### M1: Concurrent forceFlush + Shutdown Race (HIGH PRIORITY)

**Gap:** No test covers the race where `forceFlush()` adds a promise to `pendingFlushes` while `shutdown()` is being called concurrently.

**Risk:** Promise may never complete (R3 in executive summary).

**Suggested test:**
```
Two threads:
  T1: calls forceFlush() in a loop (100 iterations)
  T2: calls shutdown() after a small delay
Assert: all forceFlush promises complete within a bounded timeout
        OR are observed as failed (never stuck indefinitely)
```

---

### M2: Worker Thread Death from Unchecked Exception (HIGH PRIORITY)

**Gap:** The `catch (RuntimeException unexpected)` in `workerLoop` (lines 277–283) logs and terminates the worker. No test verifies:
1. `workerTerminated.countDown()` is called even when the exception path is taken.
2. Subsequent `shutdown()` completes (terminator waits for latch → countDown happened → exporter.shutdown() → shutdownResult completes).

**Suggested test double:** An exporter that throws on a specific call count (e.g., nth export).

---

### M3: Multiple Concurrent forceFlush Calls (MEDIUM PRIORITY)

**Gap:** `pendingFlushes` is a `List`, and multiple concurrent callers can add promises. No test verifies all promises are completed when multiple `forceFlush()` calls race.

**Suggested test:**
```
10 threads each call forceFlush() simultaneously
Assert: all 10 CompletableResultCode instances complete within timeout
        (succeed or fail, but not stuck)
```

---

### M4: exporter.shutdown() Hang (MEDIUM PRIORITY)

**Gap:** No test verifies behavior when `exporter.shutdown()` never completes (the terminator thread leaks scenario).

**Suggested test double:** An exporter whose `shutdown()` returns a `CompletableResultCode` that never completes. The test should verify that after `shutdownTimeout` elapses, the processor does NOT hang the test indefinitely — it requires a timeout bound on the test assertion.

**Note:** Current code has no timeout on `exporter.shutdown()`. This test would expose the gap (R1) without fixing it.

---

### M5: scheduleDelay Trigger (Deterministic) (MEDIUM PRIORITY)

**Gap:** All existing tests use either (a) a very long `scheduleDelay` (disabled background flush) or (b) `forceFlush()` for explicit drain. No test verifies that background flush actually triggers when `scheduleDelay` elapses without explicit signal.

**Suggested test:**
```
processor = builder.scheduleDelay(50ms).maxQueueSize(32).maxExportBatchSize(4).build()
Emit 2 spans (below batch threshold)
Sleep 200ms (> scheduleDelay)
Assert: exporter.exported.size() >= 2 (background flush triggered)
```

---

### M6: logExportFailureOnce Throttle Verification (LOW PRIORITY)

**Gap:** `logExportFailureOnce` emits only one WARN regardless of subsequent failures. No test verifies that subsequent failures do NOT produce additional log entries.

**Suggested approach:** Capture SLF4J log events via a test appender and assert that only one WARN containing the export failure message is emitted even when the exporter fails 10 times.

---

### M7: Interrupt During awaitNanos (LOW PRIORITY)

**Gap:** The `InterruptedException` path in `workerLoop` (the maintenance hazard path) is only triggered by the terminator thread's `workerThread.interrupt()` call. This path is exercised implicitly by the shutdown timeout test (if a timeout actually occurs) but not explicitly. No test isolates this code path.

**Suggested test:**
```
Build processor with never-completing exporter and shutdownTimeout=100ms
Emit spans
Call shutdown()
Assert: shutdown completes within 2s (timeout path taken)
Assert: droppedSpansAfterShutdown > 0 (queue cleared on timeout)
Assert: exportFailures counter is not inflated by the interrupt path
```

---

### M8: toSpanData() RuntimeException in onEnd (LOW PRIORITY)

**Gap:** `onEnd` catches `RuntimeException` from `span.toSpanData()` (line 98–100) with `logExportFailureOnce`. No test verifies this path:
1. The span is lost silently (no counter increment for this case specifically).
2. The processor continues working for subsequent spans.

**Suggested test double:** A `ReadableSpan` stub that throws on `toSpanData()`.

---

### M9: forceFlush After Worker Exits (LOW PRIORITY)

**Gap:** If the worker has already exited (e.g., after shutdown completed), a subsequent `forceFlush()` call hits the `shutdownRequested.get()` check (line 113) and returns `ofSuccess()`. This is the documented behavior. No test verifies this after a completed `shutdown()`.

---

## High-Risk Concurrency Tests

| # | Scenario | Determinism Strategy |
|---|----------|---------------------|
| M1 | concurrent forceFlush + shutdown | `CountDownLatch` for synchronization; bounded timeout assertion |
| M2 | worker exception death | Controlled exception injection via AtomicInteger call count |
| M3 | multiple concurrent forceFlush | `CyclicBarrier` to start all threads simultaneously |
| M7 | interrupt path | Short `shutdownTimeout` to force timeout + interrupt path |

---

## Deterministic Test Strategy for Known Behaviors

| Behavior | Strategy |
|----------|----------|
| Worker blocked in exporter | `BlockingExporter` with `CountDownLatch` (already used in existing tests) |
| Export timeout | `neverCompletesResultCode` exporter + short `exportTimeout` |
| Overflow counter | Block exporter first, then emit N+capacity spans (calling thread, no timing dependency) |
| Schedule delay flush | Short `scheduleDelay` + `Thread.sleep(2 × delay)` + assert |
| Shutdown drain | Long `scheduleDelay` (disabled), emit spans, `tp.close()`, bounded wait |

---

## Suggested Test Doubles / Fakes

| Double | Used in | Already exists? |
|--------|---------|-----------------|
| `BlockingExporter` | Overflow tests | Yes (both test files) |
| `CountingExporter` | Lifecycle, no-double-export | Yes (lifecycle test) |
| `NeverCompletingResultCodeExporter` | Timeout, shutdown hang | Partially (in Lifecycle timeout test; not for shutdown) |
| `ThrowingExporter(n)` | Worker death, export exception | Partially (exception isolation test) |
| `NeverCompleteShutdownExporter` | exporter.shutdown() hang | **Missing** |
| `InterruptTrackingExporter` | Interrupt path | **Missing** |
| `SlowExporter(delay)` | scheduleDelay trigger | **Missing** |

