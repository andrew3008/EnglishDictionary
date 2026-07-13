# ADR — TraceOperations Metering SPI Boundary

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

v1 registered `MeteredPlatformTracing` as `@Primary` `TraceOperations` bean. The decorator overrode only a subset of the wide facade; relation-aware and link-aware calls fell back to interface defaults on the decorator instance, silently degrading ROOT/DETACHED/links ([R01](../known-issues/R01.md)).

## Decision

Platform self-metrics are recorded by **`MeteredTracingImplementation`**, which decorates **`TracingImplementation`** at the internal SPI boundary.

Rules:

- Metering MUST NOT decorate the public `TraceOperations` facade.
- Metering MUST NOT create spans except by delegating to the wrapped `TracingImplementation`.
- Metering MUST NOT replace or wrap Spring `TracingObservationHandler` beans.
- When Micrometer is present, exactly one active `TracingImplementation` chain exists (`BeanTopologyTest`).

## Why metering is on `TracingImplementation`, not `TraceOperations`

- All manual span creation already converges on `TracingImplementation.startSpan(SpanSpec)` — one interception point.
- Decorating the full SPI preserves ROOT/DETACHED/links semantics (`MeteredTopologyMatrixTest`).
- Public facade stays narrow (`traceContext()` + `manual()`) with no override surface for partial delegation bugs.
- Separates **platform self-metrics** from **Micrometer Observation lifecycle** ([ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md)).

## Relationship to Micrometer Observation API

- `MeteredTracingImplementation` counts platform manual tracing operations at the SPI boundary.
- Micrometer Observation handlers remain responsible for framework observations (HTTP, `@Observed`).
- The two systems coexist under Option C hybrid model; they MUST NOT double-decorate each other's span creation paths.

## Consequences

### Positive

- R01 structurally addressed; durable topology + metering proof.
- Clear separation: metrics decorator vs span lifecycle owner.

### Negative

- Historical `MeteredPlatformTracingKnownDefectTest` archived; v1 pattern must not return.

## References

- [R01.md](../known-issues/R01.md)
- [platform-tracing-v3-observability-and-diagnostics.md](../tracing/platform-tracing-v3-observability-and-diagnostics.md)
- [ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md)
