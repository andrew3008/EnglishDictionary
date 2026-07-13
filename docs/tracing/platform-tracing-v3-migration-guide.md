# TraceOperations v3 — Migration Guide

This guide maps the **removed v1 public API** to the v3 replacement. TraceOperations v3 is a **breaking, intentional** redesign. The project was **pre-production** when the cutover happened; there is **no compatibility shim** and no deprecate-first migration path.

Facade decorators (`MeteredPlatformTracing`, `Facade*SpanBuilder`) were removed to prevent [R01](../known-issues/R01.md)-class bugs where partial decorators silently dropped ROOT/DETACHED/links semantics.

## v1 → v3 mapping

| Removed v1 API | v3 replacement |
|---|---|
| `currentTraceId()` | `traceContext().traceId()` |
| `currentSpanId()` | `traceContext().spanId()` |
| `startSpan(name, category)` | `spans().operation(name).start()` / `.run()` / `.call()` |
| `startRootSpan(...)` | `spans().operation(name).root().start()` |
| `startDetachedSpan(...)` | `spans().operation(name).detached().start()` |
| `startSpanWithLinks(...)` | `.root().linkedTo(...).start()` |
| `addLink(...)` | pre-start `.linkedTo(...)`; **no post-start replacement** |
| `inSpan(...)` | `.run()` / `.call()` / `.callChecked()` |
| `SpanRelation` | `Topology` through `.child()` / `.root()` / `.detached()` |
| `internalSpan()` / `businessSpan()` | `spans().operation(name)` |
| transport factory methods on `TraceOperations` | `spans().transport().http()/database()/rpc()/kafka()` |

## Breaking change policy

- **No compatibility shim.** v1 methods are not available on `TraceOperations`.
- **No `MeteredPlatformTracing` public decorator.** Metering is internal on `TracingImplementation` ([ADR — Metering SPI Boundary](../decisions/ADR-platform-tracing-metering-spi-boundary.md)).
- **No `Facade*SpanBuilder`.** Semantic builders live under `spans().transport()`.
- **Links are pre-start only.** There is no v3 equivalent of post-start `addLink(...)`.
- **Governed escape hatch:** `spans().fromSpec(spec)` requires `SpanSpecReason` and, for `TEMPORARY_WORKAROUND`, a `reference`.

## Common migration patterns

### Correlation in logs

```java
// v1
String id = traceOperations.currentTraceId();

// v3
String id = traceOperations.traceContext().traceId().orElse("unknown");
```

### Scoped business logic

```java
// v1
traceOperations.inSpan("process-order", SpanCategory.INTERNAL, () -> service.process(orderId));

// v3
traceOperations.spans()
        .operation("process-order")
        .run(() -> service.process(orderId));
```

### Root span with links (Kafka batch)

```java
// v1 (removed)
traceOperations.startSpanWithLinks("batch", SpanCategory.INTERNAL, links);

// v3
traceOperations.spans()
        .transport()
        .kafka()
        .consumer()
        .batch("orders")
        .root()
        .linkedTo(links)
        .run(() -> processor.processBatch(records));
```

See [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md).

### Database / RPC transport spans

```java
// v1
traceOperations.databaseSpan().system("postgresql").operation("SELECT").start();

// v3
traceOperations.spans()
        .transport()
        .database()
        .system("postgresql")
        .operation("SELECT")
        .start();
```

## SpanRelation → Topology

v1 `SpanRelation` enum mapped to v3 explicit topology setters on builders:

| v1 SpanRelation | v3 builder |
|-----------------|------------|
| CHILD (default) | `.child()` or omit when default applies |
| ROOT | `.root()` |
| DETACHED | `.detached()` |

Links policy: **ROOT + links allowed**; **DETACHED + links forbidden**; **CHILD + links forbidden**. See [ADR — Topology and Links](../decisions/ADR-platform-tracing-topology-links.md).

## Advanced / raw span escape hatches

v1 `advanced()`, `rawSpan()`, and similar wide APIs are replaced by governed `fromSpec`:

```java
SpanSpec spec = SpanSpec.builder("vendor-integration")
        .category(SpanCategory.INTERNAL)
        .child()
        .reason(SpanSpecReason.UNSUPPORTED_LIBRARY)
        .reference("PLATFORM-1234")
        .build();

traceOperations.spans().fromSpec(spec).run(action);
```

See [ADR — SpanSpec Governance](../decisions/ADR-platform-tracing-span-spec-governance.md).

## Operational notes after migration

- **Kafka batch semantic drift** is intentional v3 behavior (span kind, name, messaging attributes). Notify dashboard/alert owners before production cutover.
- **Micrometer Observation coexistence** — do not combine `@Traced` and `@Observed` on the same method; see [Observability and diagnostics](./platform-tracing-v3-observability-and-diagnostics.md).
- **Production sign-off** requires `-PrunE2e` in a Docker/Testcontainers environment — not part of the default build.

## Related documents

- [Getting started](./platform-tracing-v3-getting-started.md)
- [SpanFactory API reference](./platform-tracing-v3-span-factory-api.md)
- [ADR — v3 Public API](../decisions/ADR-platform-tracing-v3-public-api.md)
