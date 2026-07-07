# LLM Research Input: Architectural Options for PlatformDropOldestExportSpanProcessor Refactoring

> This file is optimized as input for Perplexity Deep Research or external LLM architectural scoring.
> Context is self-contained. Claims are evidence-backed. Speculation is labeled.

---

## Problem Statement

A production-critical Java class (`PlatformDropOldestExportSpanProcessor`, 575 lines) implements a custom OpenTelemetry `SpanProcessor` with drop-oldest queue semantics for enterprise tracing. The class is difficult to maintain because it packs nine distinct responsibilities into a single monolith with complex concurrency. A refactoring is planned but the optimal decomposition strategy is unknown. We need to evaluate 8 architectural variants.

The class cannot be replaced with the stock OTel `BatchSpanProcessor` because the stock BSP implements **drop-new** semantics (confirmed on SDK 1.61.0 and 1.62.0). The platform requires **drop-oldest** (newest span survives overflow).

---

## System Context

- **Platform:** OpenTelemetry Java Agent Extension (agent classloader) + Spring Boot Autoconfigure (application classloader). Cross-classloader boundary.
- **Activation:** opt-in via `platform.tracing.queue.overflow-policy=DROP_OLDEST`. Default is stock BSP (no custom processor).
- **Single-exporter constraint:** the processor wraps exactly one `SpanExporter` (fan-out via OTel Collector is the recommended alternative for multi-exporter).
- **JMX exposure:** six observability getters are exposed via a JMX MBean (`PlatformExportControl`) with a documented wire contract. Getter signatures are frozen.
- **OTel SPI wiring:** the processor is created inside an OTel `AutoConfigurationCustomizer.addSpanProcessorCustomizer()` callback. The `Builder` API is the only coupling point with the factory.

---

## Current Class Responsibilities (9 total)

1. **Bounded drop-oldest queue** — `ArrayDeque<SpanData>` + `ReentrantLock` + eviction logic
2. **Export worker lifecycle** — single daemon thread, `workerLoop()`, `awaitNanos` scheduling
3. **Timed batch export** — `exporter.export(batch).join(exportTimeoutNanos)` per batch
4. **forceFlush coordination** — promise list in `pendingFlushes` under same lock as queue
5. **Shutdown coordination** — `shutdownRequested` AtomicBoolean, separate terminator daemon thread, `exporter.shutdown()` delegation
6. **Builder + config validation** — production-safe WARN + fallback (not fail-fast)
7. **BSP config key reading** — `readBspConfigFrom(ConfigProperties)` parsing `otel.bsp.*` keys
8. **Observability counters** — four `AtomicLong` counters + two derived getters
9. **Lifecycle state guard** — `shutdownRequested` fast-path check in `onEnd` and `forceFlush`

---

## Current Behavior Summary

### Queue
- `ArrayDeque<SpanData>` with `maxQueueSize` (default 2048)
- On `onEnd`: producer thread acquires `queueLock`, checks shutdown, if full: `pollFirst()` + `droppedSpansOverflow++`, then `offerLast()`. Lock released immediately. O(1).
- `exporter.export()` always called outside the lock → `onEnd` is non-blocking even when exporter is slow.

### Worker
- Single daemon thread. Wakes on: `signalAll()` or `awaitNanos(scheduleDelay)`.
- Exports when: batch full, schedule delay elapsed and queue non-empty, forceFlush pending, or shutdown.
- Acquires `queueLock`, drains batch and pending flush promises, releases lock, then calls `exporter.export()`.

### forceFlush
- If shutdown: returns `CompletableResultCode.ofSuccess()` immediately.
- Otherwise: adds promise to `pendingFlushes` under lock, signals worker, returns promise.
- Promise is completed (succeed/fail) by the worker after the export attempt.

### Shutdown
- `shutdown()` is idempotent. Returns cached `shutdownResult` immediately.
- Sets `shutdownRequested`, signals worker.
- Starts a **third daemon thread** (terminator) that waits for worker to exit (`workerTerminated` latch with `shutdownTimeoutNanos`).
- On worker exit (or timeout): calls `exporter.shutdown()` with **no timeout**. Completes `shutdownResult` when exporter shutdown completes.

---

## Preserved Invariants

| Invariant | How Protected |
|-----------|---------------|
| Drop-oldest semantics: `pollFirst()` before `offerLast()` under lock | Queue lock; tested |
| `onEnd` never blocks the caller | `exporter.export()` outside lock; SP-05/Test2 |
| `dropped + exported == TOTAL` (no silent loss) | `droppedSpansOverflow` counter; tested |
| `shutdown()` idempotent | `AtomicBoolean.compareAndSet`; tested |
| `forceFlush()` after `shutdown()` returns `ofSuccess()` immediately | Line 113–114 |
| Six getter signatures unchanged (JMX wire contract) | `PlatformExportControl` consumes them |
| Worker is daemon thread | Line 69 |
| `null` exporter → NullPointerException (only fail-fast case) | Builder |
| Shutdown drain completes or times out in 10s | `ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT` |

---

## Known Risks (Top 5)

| Risk | Description |
|------|-------------|
| R1 — exporter.shutdown() hang | `exporter.shutdown()` called by terminator thread with no timeout. If it hangs: `shutdownResult` never completes → OTel SDK hangs. |
| R2 — double-unlock maintenance trap | Explicit `queueLock.unlock()` in `InterruptedException` path + `isHeldByCurrentThread()` guard in `finally`. Any refactoring of this block risks double-unlock (exception) or lock-leak (deadlock). |
| R3 — forceFlush promise lost during shutdown | Promise added to `pendingFlushes` after worker exits → promise never completed. |
| R4 — worker death uncaught | `catch (RuntimeException unexpected)` stops all exports. No recovery. Counter + log only. |
| R5 — logExportFailureOnce permanent throttle | One-shot log suppression, never reset. Post-recovery failures are invisible in logs. |

---

## Test Coverage Summary

| Category | Status |
|----------|--------|
| Builder validation | Well-covered (5 tests) |
| Drop-oldest queue contract | Well-covered (7 tests, identity proof) |
| forceFlush (basic drain, success) | Covered |
| shutdown (idempotency, drain, counters) | Covered |
| Export timeout | Covered |
| Export exception isolation | Covered |
| No double-export (SDK integration) | Covered |
| Concurrent forceFlush + shutdown race | **Missing** |
| Worker exception death | **Missing** |
| Multiple concurrent forceFlush | **Missing** |
| exporter.shutdown() hang | **Missing** |
| scheduleDelay trigger (deterministic) | **Missing** |
| logExportFailureOnce throttle | **Missing** |

---

## Candidate Extraction Seams

| Component | Cohesion | Coupling | Risk |
|-----------|----------|----------|------|
| C3: TimedExporter (exportBatch + counters) | High | Low | Low |
| C6: Config/Builder | High | Low | Low |
| C7: ObservabilitySnapshot (6 getters) | High | Low | Low |
| C1: BoundedDropOldestQueue | High | Medium (lock shared with worker) | Medium |
| C2: ExportWorker | High | High (owns worker thread, borrows C1 lock) | High |
| C4: ForceFlushCoordinator | Low | High (coupled to C1 lock) | Medium |
| C5: ShutdownCoordinator | Medium | High (touches C1, C2, exporter) | High |

---

## Question for External LLM Review

**Evaluate 8 architectural refactoring variants with scoring for an enterprise Java/OpenTelemetry tracing platform.**

### Context recap
- Source: 575-line class, 9 responsibilities, production-critical, opt-in activated
- Constraints: public getter signatures frozen (JMX wire contract), `Builder` API frozen (factory coupling), `onEnd` must be non-blocking, drop-oldest semantics must be exact, `shutdown()` must be idempotent
- Test coverage: substantial but missing 6 characterization tests for concurrency edge cases
- Performance baseline: JMH `QueueOfferBenchmark` p99 must not regress

### Variant definitions

**V1: Status quo + targeted fixes only**
Add `exporter.shutdown()` timeout (R1 fix), add `forceFlush`-after-shutdown test. No structural changes.

**V2: Extract TimedExporter only**
Move `exportBatch()` + export counters into a separate package-private `TimedExporter` class. Keep everything else in the monolith.

**V3: Extract Builder + Config as a separate record**
Move `Builder` into a `ProcessorConfig` record + validation utility. Reduces constructor complexity but changes no concurrency.

**V4: Extract queue into BoundedDropOldestQueue**
Move `ArrayDeque` + `queueLock` + `queueNotEmpty` + eviction into a separate class. Requires clean interface for the interrupt path (the maintenance hazard).

**V5: Extract TimedExporter + Config + Observability (3 low-risk components)**
Combine V2 + V3 + C7. Remove ~150 lines from the monolith with no concurrency changes.

**V6: Full decomposition (all 8 components as separate classes)**
C1–C8 all extracted. Worker and queue co-designed. Facade class remains for public API.

**V7: Replace internals with j.u.c.LinkedBlockingDeque**
Replace `ArrayDeque` + `ReentrantLock` with `LinkedBlockingDeque` (supports `pollFirst()`/`offerLast()` atomically). Eliminates manual lock, condition, and double-unlock hazard. Behavior change: `LinkedBlockingDeque.offerLast()` blocks if full → must use a custom eviction loop with `pollFirst()` + `offerLast()` still under external synchronization.

**V8: Replace internals with Disruptor (LMAX)**
Use `com.lmax.disruptor.RingBuffer` as the queue. Ring buffers are inherently bounded and support single-producer / single-consumer patterns efficiently. Main constraint: Disruptor's overflow policy is configurable but fixed per-buffer, and its threading model differs significantly from the current design. Adds a new dependency.

### Scoring criteria (weight each 0–10)

1. **Behavior preservation** (must-have): does the variant maintain all preserved invariants?
2. **Concurrency hazard reduction**: does it eliminate or reduce the double-unlock / lock-leak / R3 risks?
3. **Test isolation improvement**: does it make the missing characterization tests easier to write?
4. **Refactoring risk** (inverse): lower is better — rate the risk of introducing regressions.
5. **Performance neutrality**: no regression in `QueueOfferBenchmark` p99.
6. **Incremental deliverability**: can it be done in a single PR without breaking the main branch?
7. **Future extensibility**: does it create clean seams for future features (priority eviction, multi-exporter, Micrometer)?
8. **Dependency footprint**: does it add new external dependencies?

### Expected output format

For each variant: brief description, scores on all 8 criteria, overall recommendation (GO / CAUTION / NO GO), and the single biggest risk.

Also: what is the recommended variant ordering (if V5 is safer than V6, should V5 be a prerequisite for V6)?
