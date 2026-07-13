# TraceOperations v3 — Kafka Batch Links

Kafka batch consumer processing uses **ROOT + pre-start links** to correlate a single batch span with individual message traces. This is the primary public example for span links in v3.

## Recommended pattern

```java
traceOperations.spans()
        .transport()
        .kafka()
        .consumer()
        .batch("orders")
        .root()
        .linkedTo(messageContexts)
        .run(() -> processor.processBatch(records));
```

`messageContexts` is a `RemoteSpanLink[]` (varargs) built from record headers. The platform `KafkaBatchLinksAspect`, which advises `@KafkaListener(batch="true")` methods, uses this same API internally.

## Building link contexts

### From structured link contexts

```java
RemoteSpanLink link = RemoteSpanLink.sampled(traceId, spanId);
// or with tracestate:
RemoteSpanLink link = new RemoteSpanLink(traceId, spanId, (byte) 1, traceState);
```

### From W3C traceparent headers

OTel propagator extraction remains legitimate for **reading** remote context from headers. Use builder `fromTraceparent` when you have traceparent strings:

```java
.fromTraceparent(traceparent1, traceparent2)
```

For lenient extraction loops (skip malformed headers), parse with `TraceparentParser.parseTraceparent(header)` and collect valid links before calling `linkedTo(...)`.

**Tracestate is preserved** when present on the extracted link context.

## Topology rules

| Topology | Links | Batch use case |
|----------|-------|----------------|
| ROOT | Allowed | **Required pattern** for batch processing |
| CHILD | Forbidden | Do not attach links to child spans |
| DETACHED | Forbidden | Fail fast |

`.root()` ensures the batch span starts a new trace even when a parent context is active on the listener thread.

## Batch destination naming

The batch builder `batch(destination)` argument drives span naming and `messaging.destination.name`:

| Batch contents | Destination value |
|----------------|-------------------|
| Single topic | Topic name |
| Multiple topics | Kafka listener id |
| Fallback | advised method name (`pjp.getSignature().getName()`) |

This matches `KafkaBatchLinksAspect` destination resolution in autoconfigure.

## Semantic drift (intentional v3 behavior)

v3 batch spans differ from the old raw OTel aspect spans:

| Aspect | v1 / raw OTel aspect | v3 platform batch API |
|--------|----------------------|------------------------|
| Span kind | Often `INTERNAL` | `KAFKA_CONSUMER` |
| Span name | `<method> process batch` | `<destination> process` |
| Messaging attributes | Often absent | `messaging.system`, `messaging.destination.name`, `messaging.operation=process` |
| Category | Generic internal | `KAFKA_CONSUMER` / `platform.trace.type=kafka_consumer` |

**Operational action:** notify dashboard and alert owners about this drift before production rollout. Existing queries filtering on old span names or kinds will need updates.

## What changed in the aspect boundary

- **Removed:** raw OTel `Tracer` / `SpanBuilder` span creation in `KafkaBatchLinksAspect`.
- **Kept:** OTel propagator extraction from record headers to build remote link contexts.
- **Added:** routing through `spans().transport().kafka().consumer().batch(...).root().linkedTo(...)`.

Proof: `KafkaBatchAspectMigrationTest`, `KafkaBatchSpanBuilderIntegrationTest`.

## Related documents

- [SpanFactory API reference](./platform-tracing-v3-span-factory-api.md)
- [ADR — Kafka Batch Links](../decisions/ADR-platform-tracing-kafka-batch-links.md)
- [Production readiness](./platform-tracing-v3-production-readiness.md)
