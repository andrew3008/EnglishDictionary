# ADR: SpanFactory API Shape

## Status

Accepted.

## Decision

The pre-production public tracing API is renamed as follows:

```text
ManualTracing -> SpanFactory
TraceOperations.manual() -> TraceOperations.spans()
ManualTracing.operation(String) -> SpanFactory.operation(String)
ManualTracing.spanFromSpec(SpanSpec) -> SpanFactory.fromSpec(SpanSpec)
ManualTracing.transport() -> SpanFactory.transport()
```

Approved call sites:

```java
traceOperations.spans().operation("recalculate")
traceOperations.spans().transport()
traceOperations.spans().fromSpec(spec)
```

## Rationale

`manual` describes instrumentation style, not the domain role. `SpanFactory` describes the capability object's creation role directly.

`TraceOperations` remains a small capability entrypoint. Flattening span creation methods onto `TraceOperations` was rejected because it increases god-object risk and makes future growth less controlled.

`operation(String)` at the root was rejected as ambiguous. `operation(String)` inside `SpanFactory` is accepted because `spans()` and `SpanFactory` already provide the span namespace.

`operationSpan(String)` was rejected as redundant and inconsistent with sibling methods `transport()` and `fromSpec(...)`.

No aliases, bridges, or deprecated compatibility methods are provided because the tracing API is pre-production.

## Consequences

Consumers must update source code to the new names. Active Java source must not reference the old public API type or methods.
