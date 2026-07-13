# ADR — TraceOperations Kafka Batch Links

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

Kafka batch listeners process many records that may originate from different producer traces. A single batch span should correlate to all message traces via **span links**, not by picking one message as parent. v1 used raw OTel span creation in `KafkaBatchLinksAspect`; v3 requires routing through the governed platform API.

## Decision

Kafka batch processing uses:

```java
traceOperations.manual()
        .transport()
        .kafka()
        .consumer()
        .batch(destination)
        .root()
        .linkedTo(messageContexts)
        .run(() -> processor.processBatch(records));
```

## Why Kafka batch uses ROOT + links

- **ROOT** — batch processing is a new logical operation; it must not become a CHILD of an arbitrary message trace or listener thread context.
- **Links** — each record's producer trace is referenced without forcing a single parent span.
- **DETACHED + links** is forbidden — links require ROOT topology per [ADR-platform-tracing-topology-links.md](./ADR-platform-tracing-topology-links.md).

## Propagator extraction

- OTel propagator extraction from record headers remains legitimate for **reading** remote context.
- **`tracestate` is preserved** on extracted link contexts when present.
- Lenient parsing: `RemoteContext.parseTraceparent` for batch loops; strict: `fromRemoteContext` on builders.

## Aspect migration (B03)

- **Removed:** raw OTel `Tracer` / `SpanBuilder` creation in `KafkaBatchLinksAspect`.
- **Added:** injection of `TraceOperations`; batch spans via v3 batch builder API.
- `TracedAspect` already used `manual().operation(...).start()` — unchanged.

## Destination naming

| Batch contents | `batch(destination)` value |
|----------------|----------------------------|
| Single topic | Topic name |
| Multiple topics | Kafka listener id |
| Fallback | advised method name (`pjp.getSignature().getName()`) |

## Intentional semantic drift

v3 emits `KAFKA_CONSUMER` spans with messaging semconv attributes and `<destination> process` naming. This replaces old `INTERNAL` / `<method> process batch` aspect spans. Dashboard and alert owners must be notified before production rollout.

## Consequences

### Positive

- Single creation boundary for batch spans; aspect boundary closed.
- Semconv-aligned Kafka consumer category for batch work.

### Negative

- Breaking change for queries keyed on old span names/kinds.

## References

- [platform-tracing-v3-kafka-batch-links.md](../tracing/platform-tracing-v3-kafka-batch-links.md)
- [ADR-platform-tracing-topology-links.md](./ADR-platform-tracing-topology-links.md)
- `KafkaBatchAspectMigrationTest`, `KafkaBatchSpanBuilderIntegrationTest`
