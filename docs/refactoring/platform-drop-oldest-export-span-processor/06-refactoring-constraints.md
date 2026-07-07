# Refactoring Constraints: PlatformDropOldestExportSpanProcessor

> Hard constraints must not be violated. Soft constraints require justification if violated.
> All constraints are evidence-based.

---

## Public API Compatibility Constraints

### Hard constraints (cannot break without a coordination plan)

| API element | Consumer | Constraint |
|-------------|----------|------------|
| `builder(SpanExporter exporter)` static factory | `PlatformExportProcessorFactory.java:96` | Signature must be preserved |
| `Builder.readBspConfigFrom(ConfigProperties)` | `PlatformExportProcessorFactory.java:98` | Must continue to read `otel.bsp.*` keys |
| `Builder.maxQueueSize(int)` | Tests + factory | Method name and parameter type |
| `Builder.maxExportBatchSize(int)` | Tests + factory | Method name and parameter type |
| `Builder.scheduleDelay(Duration)` | Tests + factory | Method name and parameter type |
| `Builder.exportTimeout(Duration)` | Tests + factory | Method name and parameter type |
| `Builder.shutdownTimeout(Duration)` | Tests + factory | Method name and parameter type |
| `Builder.build()` | Factory, tests | Return type must be `PlatformDropOldestExportSpanProcessor` |
| `getDroppedSpansOverflow()` → `long` | `PlatformExportControl.java:47`, JMX | Signature |
| `getDroppedSpansAfterShutdown()` → `long` | `PlatformExportControl.java:52`, JMX | Signature |
| `getExportFailures()` → `long` | `PlatformExportControl.java:59`, JMX | Signature |
| `getExportTimeouts()` → `long` | `PlatformExportControl.java:64`, JMX | Signature |
| `getQueueCapacity()` → `int` | `PlatformExportControl.java:71`, JMX | Signature |
| `getQueueSize()` → `int` | `PlatformExportControl.java:77`, JMX | Signature |

**Rationale:** `PlatformExportControl` is registered as a JMX MBean. Its attribute names and types are part of the JMX wire contract documented in `docs/architecture/ADR-jmx-wire-map-contract.md`. Changing getter signatures breaks the JMX attribute table without a coordinated MBean schema migration.

---

## OpenTelemetry Contract Constraints

| Constraint | Source |
|------------|--------|
| Must implement `SpanProcessor` interface | OTel SDK contract |
| `onStart(Context, ReadWriteSpan)` must be overridden (abstract in SDK) | Confirmed by compilation error |
| `isStartRequired()` must return `false` (processor does not participate at span start) | Current behavior; `PlatformCompositeSpanProcessor` respects this |
| `isEndRequired()` must return `true` | Processor only works at span end |
| `forceFlush()` must return a non-null `CompletableResultCode` | OTel SDK calls this during `SdkTracerProvider.forceFlush()` |
| `shutdown()` must be idempotent (second call returns same result) | Tested; OTel SDK may call `shutdown()` from multiple paths |
| `forceFlush()` after `shutdown()` must not deadlock or throw | Documented in ADR; returns `ofSuccess()` |
| Must accept only `SpanData` snapshots in the queue (not live `ReadableSpan` refs) | ADR contract: avoids lifecycle/visibility problems |
| Must not call `exporter.export()` while holding any lock that `onEnd` also acquires | Fundamental non-blocking guarantee (SP-05 / Test 2) |

---

## Binary / Source Compatibility Concerns

| Concern | Impact |
|---------|--------|
| Class must remain `public final` in the same package | `PlatformExportProcessorFactory` imports it directly. Package change requires import updates everywhere. |
| Inner class `Builder` must remain a `public static final class` | Used externally via `PlatformDropOldestExportSpanProcessor.builder(...)` |
| `@Slf4j` on the class | Generates a static `log` field. Any extracted inner class should use its own logger or accept the parent logger. |
| `implements SpanProcessor` (not `ExtendedSpanProcessor`) | The processor does not participate in `onEnding`. Adding `ExtendedSpanProcessor` would change composite behavior. |

---

## Observability Compatibility Concerns

| Concern | Impact |
|---------|--------|
| Counter semantics: `exportTimeouts ⊆ exportFailures` | Both `exportTimeouts` and `exportFailures` are incremented on timeout. If C3 (TimedExporter) is extracted, both increments must travel together. |
| `droppedSpansOverflow + exported == TOTAL` invariant | Tested by `dropOldestPreservesNewestSpans`. The eviction counter must be incremented atomically with the eviction in the same lock section. |
| JMX attribute types | Getters return `long`/`int`. No `AtomicLong.get()` may be exposed directly as a JMX attribute — the getters are the adapter layer. |
| `logExportFailureOnce` is permanent | The one-shot log throttle is observable behavior (SRE may rely on "one WARN in startup logs"). Cannot reset without changing monitoring behavior. |

---

## Performance Constraints

| Constraint | Evidence |
|------------|---------|
| `onEnd` must not block the caller thread | SP-05 / Test 2: verified via latch in a separate test thread |
| Critical section in `enqueueWithDropOldest` must be microsecond-scale | ADR §Queue: "атомарного eviction допускается — на базе ArrayDeque + ReentrantLock... microseconds" |
| `exporter.export()` must always be called outside `queueLock` | Non-blocking invariant; `QueueOfferBenchmark` measures this path |
| JMH baseline must be re-run after structural changes | `QueueOfferBenchmark` provides the p99 envelope; any regression in saturated-mode is a constraint violation |
| Producer lock hold time: bounded by queue size O(1) + signal | `ArrayDeque.pollFirst()` and `offerLast()` are O(1); no sorting or full traversal |

---

## Thread-Safety Constraints

| Constraint | Evidence |
|------------|---------|
| All `queue` access must be under `queueLock` | Current invariant; `drainBatchLocked` called under lock only |
| All `pendingFlushes` access must be under `queueLock` | Same lock as queue — atomic visibility together |
| `shutdownRequested` must be `AtomicBoolean` (or equivalent) | Fast-path read in `onEnd` without lock |
| All observability counters must be `AtomicLong` (or equivalent) | JMX reads from different threads; no lock |
| `workerTerminated` must be a `CountDownLatch` (or equivalent synchronizer) | Terminator thread must block on it; no polling |
| Worker thread must be daemon | Line 69; JVM must not wait for it on application exit |

---

## Rollback Strategy

Per ADR-drop-oldest-export-processor-v1.md:

> Снятие env-var / sys-prop `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` восстанавливает default (stock BSP).

This means:
1. The refactored processor must remain **opt-in** (no change to `QueueOverflowPolicy.UPSTREAM` behavior).
2. `PlatformExportProcessorFactory.isExplicitUpstream()` must continue to serve as the gate.
3. If the refactored processor introduces a regression, the operator can roll back by unsetting the env-var — no code deployment required.

For the refactoring itself:
- **Feature flag:** not applicable (this is a compile-time replacement, not a runtime toggle).
- **Migration path:** extract components incrementally, keeping the public facade class (`PlatformDropOldestExportSpanProcessor`) intact and delegating to the new components. Do not remove the facade class until all consumers are updated.
- **Test gate:** All existing tests must pass before merging. New characterization tests (see `05-test-coverage-and-gaps.md`) must pass before each extraction step.
- **Benchmark gate:** `QueueOfferBenchmark` must show no regression in `dropOldestOfferSteady` and `dropOldestOfferSaturatedEviction` compared to the pre-refactoring baseline.

---

## Summary: Cannot Refactor Without

1. All existing tests passing (no behavior change).
2. Characterization tests M1 (concurrent forceFlush + shutdown) and M2 (worker exception death) passing.
3. `QueueOfferBenchmark` baseline captured before any change.
4. The six public getter signatures preserved unchanged.
5. The `Builder` public API preserved unchanged.
6. `exporter.export()` still called outside `queueLock`.
7. `onEnd` non-blocking property preserved (SP-05 / Test 2 passes).
