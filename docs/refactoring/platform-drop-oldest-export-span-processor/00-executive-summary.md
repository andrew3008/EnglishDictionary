# Executive Summary: PlatformDropOldestExportSpanProcessor Dossier

| Field | Value |
|-------|-------|
| Target class | `space.br1440.platform.tracing.otel.extension.processor.PlatformDropOldestExportSpanProcessor` |
| File | `platform-tracing-otel-extension/.../processor/PlatformDropOldestExportSpanProcessor.java` |
| Lines | 575 |
| Status | Production-critical, opt-in active |
| ADR | `docs/decisions/ADR-drop-oldest-export-processor-v1.md` |
| Investigation date | 2026-06-30 |

---

## Current Role

`PlatformDropOldestExportSpanProcessor` is the platform's custom OTel `SpanProcessor` that replaces the stock `BatchSpanProcessor` (BSP) when `platform.tracing.queue.overflow-policy=DROP_OLDEST` is configured. Its core contract:

- Maintains a **bounded FIFO queue** of `SpanData` snapshots.
- On overflow: evicts the **oldest** entry and accepts the newest (drop-oldest semantics, inverse of stock BSP drop-new).
- Exports batches asynchronously via a dedicated daemon worker thread.
- Supports `forceFlush`, `shutdown` (idempotent, drains queue), export timeout, and full observability counters.
- Exposes six observability getters used by `PlatformExportControl` (JMX MBean) and `dropped-span-reasons.md` taxonomy.

It is created exclusively by `PlatformExportProcessorFactory.maybeReplaceExportProcessor()`, wired into the OTel SPI customizer pipeline, and registered in JMX via `PlatformTracingJmxRegistrar`.

---

## Why the Class Is Hard to Maintain

The class packs **nine distinct responsibilities** into 575 lines with no internal boundaries:

1. **Bounded drop-oldest queue** (`ArrayDeque` + `ReentrantLock` + eviction logic)
2. **Export worker lifecycle** (daemon thread, worker loop, scheduling logic)
3. **Timed batch export** (`exportBatch`, `join(exportTimeoutNanos)`)
4. **forceFlush coordination** (`pendingFlushes` list, promise completion)
5. **Shutdown coordination** (separate daemon terminator thread, `workerTerminated` latch, `exporter.shutdown()`)
6. **Builder + config validation** (production-safe WARN + fallback pattern)
7. **BSP config key reading** (`readBspConfigFrom`, parsing `otel.bsp.*` keys)
8. **Observability counters** (four `AtomicLong` counters + two getters)
9. **Lifecycle state guard** (`shutdownRequested AtomicBoolean`)

The concurrency model is especially complex: the worker loop uses a non-standard pattern with an **explicit early `queueLock.unlock()`** inside the `InterruptedException` handler, guarded by `isHeldByCurrentThread()` in the `finally` block. This prevents double-unlock but is a significant maintenance trap — any refactoring that restructures the try/catch hierarchy can silently re-introduce it.

---

## Top Production Risks

| # | Risk | Evidence |
|---|------|----------|
| R1 | **Exporter.shutdown() hang** — shutdown terminator thread calls `exporter.shutdown()` and waits for its `CompletableResultCode`. If the exporter never completes, the terminator thread leaks and `shutdownResult` never completes, potentially blocking OTel SDK graceful shutdown. | `PlatformDropOldestExportSpanProcessor.java:172–188` |
| R2 | **double-unlock maintenance trap** — explicit `queueLock.unlock()` on line 254 inside `InterruptedException` handler; `finally` on line 267 guards with `isHeldByCurrentThread()`. Any future edit to the try/finally structure can re-introduce double-unlock or lock-leak silently. | Lines 240–270 |
| R3 | **forceFlush promise may not complete during concurrent shutdown** — if shutdown triggers while a `forceFlush` promise is in `pendingFlushes` but the worker has already exited its loop, the promise is never completed. | `workerLoop()` + `forceFlush()` interaction |
| R4 | **Worker daemon thread death from unchecked exception** — the `catch (RuntimeException unexpected)` guard catches and logs but the worker stops. Counter and log only; no alerting path. No recovery mechanism. | Lines 277–283 |
| R5 | **No shutdown timeout for exporter.shutdown()** — the terminator thread waits for `exporter.shutdown()` with no second timeout. The per-batch `exportTimeout` is not applied here. | Lines 172–188 |
| R6 | **`logExportFailureOnce` throttle is permanent** — after first failure, all subsequent failures are silently swallowed (only counter increments). If the exporter recovers and fails again later, the second failure produces no log at all. | `AtomicBoolean exportFailureLogged`, line 404–409 |
| R7 | **toSpanData() materializes full immutable snapshot on every sampled span end** — called in the producer thread under no lock (correct), but the allocation cost is per-span. Under high load, GC pressure from `SpanData` objects accumulates. | Line 96–101 |
| R8 | **scheduleDelay trigger non-deterministic under test** — `awaitNanos` may spuriously wake; no test exercises the scheduleDelay flush path deterministically. | `workerLoop()` lines 244–261 |
| R9 | **Single-exporter constraint** — if `PlatformExportProcessorFactory` detects multi-exporter it falls back to stock BSP, but the processor itself has no guard. If used directly (e.g., in tests or future wiring) with a multi-fan-out exporter, only one underlying transport gets the shutdown drain. | `PlatformExportProcessorFactory.java:61–69` |
| R10 | **`droppedSpansAfterShutdown` race** — the double-check under lock (line 210) is correct, but the fast path check on line 90 is unsynchronized. Between fast-path pass and lock acquisition, `shutdownRequested` can transition, causing the span to be enqueued and potentially lost (counted as overflow, not shutdown). Acceptable in practice but semantically ambiguous. | Lines 90–103, 210–213 |

---

## Recommended Next Step Before Refactoring

**Do not refactor yet.** The following must be completed first:

1. **Add characterization tests** for all identified gaps (see `05-test-coverage-and-gaps.md`), particularly:
   - Concurrent `forceFlush + shutdown` race
   - Worker daemon death from unchecked exception
   - Multiple concurrent `forceFlush` calls
   - `exporter.shutdown()` hang (with bounded termination)
2. **Run `QueueOfferBenchmark` (JMH)** to establish a reproducible performance baseline before any structural change.
3. **Lock the public API surface** with a source-compatibility test so future PRs cannot silently change the six getter signatures or `Builder` methods consumed by `PlatformExportControl`.

---

## Generated Investigation Files

| File | Contents |
|------|----------|
| `00-executive-summary.md` | This file |
| `01-current-behavior.md` | Full lifecycle, queue, export, forceFlush, shutdown, counters, builder behavior |
| `02-concurrency-model.md` | Shared state, thread ownership, lock usage, worker state machine, known hazards |
| `03-adjacent-code-map.md` | All classes, configs, tests, and docs that affect this processor |
| `04-responsibility-decomposition.md` | Current responsibilities + candidate extracted components |
| `05-test-coverage-and-gaps.md` | Existing tests grouped by behavior + missing tests required before refactoring |
| `06-refactoring-constraints.md` | Hard constraints for production-safe refactoring |
| `07-llm-research-input.md` | Optimized input for Perplexity/external LLM architectural option scoring |
