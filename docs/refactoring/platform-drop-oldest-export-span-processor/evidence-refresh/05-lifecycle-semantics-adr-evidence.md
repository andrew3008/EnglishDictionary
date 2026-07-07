# 05 — Lifecycle Semantics: ADR Evidence

> **Changing shutdown/forceFlush semantics is an ADR-level decision, not a refactoring language choice.**
>
> Evidence-first. All citations are to actual repository files read on 2026-06-30.
> Line references are to `PlatformDropOldestExportSpanProcessor.java` unless stated otherwise.

---

## Current `shutdown()` Code Path (with Line Citations)

```
shutdown()  [lines 131–187]
│
├─ Line 132: shutdownRequested.compareAndSet(false, true)
│      if already true → return shutdownResult (idempotent)
│
├─ Lines 136–141: queueLock.lock() → queueNotEmpty.signalAll() → queueLock.unlock()
│      (wakes worker thread)
│
└─ Lines 143–185: new Thread("platform-tracing-drop-oldest-shutdown") started as daemon
       │
       ├─ Line 146: workerTerminated.await(shutdownTimeoutNanos, NANOSECONDS)
       │      [bounded wait for worker — uses shutdownTimeoutNanos = 10s default]
       │
       ├─ Lines 152–164: if timeout:
       │      log.warn(...)
       │      workerThread.interrupt()
       │      queueLock.lock() → droppedSpansAfterShutdown.addAndGet(queue.size()) → queue.clear() → unlock
       │
       ├─ Line 168: exporterShutdown = exporter.shutdown()   ← *** NO TIMEOUT APPLIED HERE ***
       │      [if exporter.shutdown() throws RuntimeException → lines 169–174: shutdownResult.fail(); return]
       │      [if exporter.shutdown() never completes → shutdownResult NEVER completes]
       │
       └─ Lines 176–182: exporterShutdown.whenComplete(() -> {
              if (exporterShutdown.isSuccess()) shutdownResult.succeed();
              else shutdownResult.fail();
          })
```

**`shutdownResult` completion mechanism:** via `whenComplete` callback on `exporter.shutdown()` return value (line 176). The callback thread is exporter-implementation-specific. If `exporter.shutdown()` never completes, `shutdownResult` is permanently incomplete and the terminator daemon thread leaks.

---

## Current `forceFlush()` Code Path (with Line Citations)

```
forceFlush()  [lines 113–128]
│
├─ Line 114: if (shutdownRequested.get()) return CompletableResultCode.ofSuccess()
│      [early return: no promise created, no drain]
│
├─ Line 118: CompletableResultCode result = new CompletableResultCode()
│
├─ Lines 119–125: queueLock.lock()
│      pendingFlushes.add(result)   [line 121]
│      queueNotEmpty.signalAll()    [line 122]
│      queueLock.unlock()
│
└─ Line 127: return result
       [caller must join() to wait — promise completed asynchronously by worker]
```

**Promise completion:** worker loop, in `tryExport()` (lines 337–344), iterates `flushPromises` and calls `promise.succeed()` or `promise.fail()` after the export attempt.

---

## Current `exporter.shutdown()` Call

**Line 168:** `exporterShutdown = exporter.shutdown();`

**No timeout.** The per-batch `exportTimeoutNanos` (line 360 in `exportBatch()`) applies only to individual `exporter.export()` calls, not to `exporter.shutdown()`. The shutdown terminator thread waits for `workerTerminated` with `shutdownTimeoutNanos` (line 146), but this timeout is for the worker phase. Once the worker phase completes, `exporter.shutdown()` is called without any further deadline.

---

## `shutdownResult` Completion: via `whenComplete` on `exporter.shutdown()`

Source: lines 176–182:

```java
exporterShutdown.whenComplete(() -> {
    if (exporterShutdown.isSuccess()) {
        shutdownResult.succeed();
    } else {
        shutdownResult.fail();
    }
});
```

The `whenComplete` callback is registered on the `CompletableResultCode` returned by `exporter.shutdown()`. The callback executes in whichever thread completes the exporter's shutdown (exporter-specific). If the exporter's code never calls `.succeed()` or `.fail()` on its returned code, `shutdownResult` is permanently incomplete.

**Note:** Exception path at lines 169–174 is bounded — if `exporter.shutdown()` throws `RuntimeException`, `shutdownResult.fail()` is called immediately. The unbounded case is only when `exporter.shutdown()` returns a code that never completes.

---

## What "Bounded Shutdown" Changes from Current Observable Behavior

| Dimension | Current behavior | Proposed (PR-3) behavior | Observable change? |
|-----------|-----------------|--------------------------|-------------------|
| Worker phase timeout | `workerTerminated.await(shutdownTimeoutNanos, NANOSECONDS)` — bounded at 10s | Same (preserving worker phase bound) | No |
| `exporter.shutdown()` | NO timeout — if exporter hangs, terminator thread leaks forever | Bounded timeout (value to be decided) | **YES** |
| `shutdownResult` on exporter hang | Never completes — OTel SDK hangs indefinitely | Completes as FAIL after exporter timeout | **YES** |
| `shutdownResult` when exporter times out | N/A (no timeout, so this case doesn't exist) | `shutdownResult.fail()` with WARN log | **YES** |
| Terminator thread lifetime | May run indefinitely if exporter hangs | Bounded — exits after exporter timeout or exception | **YES** |

**Bounded shutdown changes observable behavior for any caller awaiting `processor.shutdown().join()`**, particularly the OTel SDK's `SdkTracerProvider.shutdown()` which waits for all processor shutdown codes.

---

## What "Deterministic forceFlush Completion" Changes

| Dimension | Current behavior | Proposed (PR-3) behavior | Observable change? |
|-----------|-----------------|--------------------------|-------------------|
| forceFlush promise during race with shutdown | May never complete (R3 — confirmed race) | Must always complete as success or fail | **YES** |
| forceFlush after shutdown | Returns `ofSuccess()` immediately (line 114) | Same — preserved as explicit policy | No |
| forceFlush during SHUTTING_DOWN | Promise may be added to pendingFlushes after worker has drained it — never completed | Worker or lifecycle must drain and complete all pendingFlushes during shutdown drain phase | **YES** |
| forceFlush after TERMINATED | Same as current: shutdownRequested=true → ofSuccess() immediately | Same — `ofSuccess()` returned (no pending work) | No |

---

## Are These Changes Observable? (Yes/No + Evidence)

| Change | Observable? | Evidence |
|--------|------------|---------|
| Bounded `exporter.shutdown()` timeout | **YES** | Applications or test harnesses that currently rely on `shutdown().join()` blocking until exporter completes will now see it complete sooner (with FAIL). `02-concurrency-model.md` §Shutdown Thread: "If it hangs, terminator thread leaks and shutdownResult is never completed." |
| `shutdownResult.fail()` on exporter timeout | **YES** | Any caller checking `isSuccess()` after `shutdown().join()` will now receive `false` in the timeout case instead of hanging. This may affect SRE alerting or test assertions. |
| Deterministic forceFlush completion | **YES** | Any application calling `forceFlush().join(timeout)` in a context where shutdown may race will now receive `false` (fail) instead of hanging. `02-concurrency-model.md` §Pending forceFlush: R3 race scenario documented. |
| Worker phase shutdown timeout (unchanged) | No | Already bounded by `shutdownTimeoutNanos`. |

---

## ADR Decision Options

| Option | Description | Semantic change? | Recommended? |
|--------|-------------|-----------------|--------------|
| 1 | **Preserve current semantics entirely** — no timeout on `exporter.shutdown()`, R3 race preserved as known behavior | No — preserves bugs | No — defeats refactoring purpose |
| 2 | **Bounded timeout → fail `shutdownResult`** — after N seconds waiting for exporter.shutdown(), complete `shutdownResult` as FAIL | YES — `shutdownResult` now completes in bounded time | YES for R1 fix |
| 3 | **Bounded timeout → succeed `shutdownResult`** — after drain, treat exporter.shutdown() timeout as best-effort success | YES — callers may get misleading success signal | No — semantically incorrect: success without confirmed exporter shutdown |
| 4 | **forceFlush during SHUTTING_DOWN joins drain** — promises added before or during SHUTTING_DOWN are included in the shutdown drain | YES — eliminates R3 race | YES for R3 fix |
| 5 | **forceFlush after TERMINATED → ofSuccess()** — current behavior already does this (line 114) | No — preserves current behavior | Already implemented; document explicitly |

**Recommended combination:** Options 2 + 4 + 5. This eliminates both R1 (unbounded shutdown) and R3 (forceFlush race) while preserving the existing post-shutdown `ofSuccess()` behavior.

**Option 2 timeout value**: must be explicit in ADR. Plan does not specify the timeout value. Suggested: same as `shutdownTimeoutNanos` (10s default) but configurable via a second shutdown phase parameter, or fixed at `shutdownTimeoutNanos` total including both phases.

---

## Which Options Are Semantic Changes vs Pure Refactoring

| Options | Classification |
|---------|---------------|
| Options 1, 5 | Pure preservation — no semantic change |
| Options 2, 3, 4 | **Semantic changes** — observable behavior change for any caller of `shutdown()` or `forceFlush()` |
| Decomposing into ExportBuffer/ExportWorker/ProcessorLifecycle (if semantics preserved) | Pure refactoring |
| Adding bounded timeout (Option 2) | Semantic change |
| Fixing R3 forceFlush race (Option 4) | Semantic change (from "may hang" to "deterministically fails") |

---

## Recommended Option with Rationale

**Adopt Options 2 + 4 (bounded shutdown + deterministic forceFlush), document as ADR-level decisions.**

Rationale:
- R1 (unbounded shutdown) and R3 (forceFlush race) are confirmed bugs, not design choices. Fixing bugs is expected, but changes in observable failure modes must be documented.
- Option 2: `shutdownResult` completing as FAIL after timeout is strictly better than hanging indefinitely. Any caller that checks `isSuccess()` and acts on failure will now see predictable behavior.
- Option 4: Ensuring forceFlush promises complete during shutdown drain is strictly better than leaving them permanently incomplete. Any caller doing `forceFlush().join(timeout)` will now receive a deterministic result.
- Option 5 (forceFlush after TERMINATED → ofSuccess()) is already implemented and correct — preserve it explicitly.

---

## Required Test Gates Before Merge (PR-3)

| Gate (from plan §5) | Test ID | Verification |
|--------------------|---------|-------------|
| No forceFlush promise permanently incomplete during concurrent shutdown | M1 | Concurrent `forceFlush + shutdown` with `CyclicBarrier`; both `join(bounded)` must complete |
| Multiple concurrent forceFlush promises all completed | M2 | N threads call `forceFlush()` concurrently; all N promises must complete |
| exporter.shutdown() hang documented as @Disabled known failure | M3 | `@Disabled` with reference to R1 issue; `NeverCompleteShutdownExporter`; no `join()` without bounded timeout |
| Worker interrupt path completes shutdown | M6 | Short `shutdownTimeout` → worker interrupted → `droppedAfterShutdown > 0` and `shutdownResult.isDone()` |
| `shutdownResult` never permanently incomplete | All M1/M2/M3/M6 | After bounded wait: `shutdownResult.isDone()` must be true |

---

## Explicit Statement

> **Changing `shutdown()` or `forceFlush()` semantics — including adding a bounded timeout to `exporter.shutdown()` and eliminating the forceFlush/shutdown race — is an ADR-level decision, not a refactoring language choice.**
>
> These changes alter the observable behavior of a production-critical component. They must be captured in a committed Architecture Decision Record (Proposed status until approved) before implementation begins in PR-3. The ADR must document:
> - The current observed behavior (with line citations)
> - The proposed new behavior
> - The specific timeout value(s) chosen and their rationale
> - The decision on forceFlush-during-SHUTTING_DOWN semantics
> - The test gates confirming the new behavior
> - The monitoring/SRE impact (callers of shutdown/forceFlush that may observe different behavior)
