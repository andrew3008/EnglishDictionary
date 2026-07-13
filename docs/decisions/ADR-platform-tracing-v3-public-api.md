# ADR — TraceOperations v3 Public API

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-07-07 |
| Context | TraceOperations v3 refactoring |

## Context

TraceOperations v1 exposed a wide facade: correlation helpers, many `startSpan*` variants, `SpanRelation`, transport factories, `inSpan`, post-start `addLink`, and facade decorators. Partial decorators (`MeteredPlatformTracing`) caused silent semantic loss ([R01](../known-issues/R01.md)). The project was pre-production; a narrow, governed public API was required.

## Decision

The v3 public `TraceOperations` interface exposes **exactly two** methods:

```text
traceContext()  — read-only correlation
manual()        — governed manual span creation
```

All span creation routes through `TracingImplementation.startSpan(SpanSpec)` internally. No v1 methods remain on the public interface. No compatibility shim.

## Why `traceContext()` over `current()` / `currentContext()`

- **`traceContext()`** names a **read-only view** (`TraceContextView`) without implying mutable context manipulation or OTel SDK ownership.
- **`current()`** suggests imperative context switching (conflicts with OTel `Context.current()` semantics and encourages SDK leakage).
- **`currentContext()`** collides with Micrometer/OTel naming and blurs read vs write responsibilities.
- The view returns `Optional` correlation fields only — no `Context`, `Span`, or `SpanContext` in public API.

## Why `manual()` is acceptable next to auto-instrumentation

Auto-instrumentation (OTel Agent, Spring/Micrometer Observation, `@Traced`) remains the default. `manual()` is the **explicit, opt-in** counterpart for cases auto-instrumentation cannot cover (custom business operations, governed transport spans, batch links). Hybrid coexistence is defined in [ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md) (Option C, Accepted).

## Why `operation(name)` replaced `internalSpan` / `businessSpan`

- v1 category-split factories (`internalSpan()`, `businessSpan()`) duplicated entry points without adding topology or semconv value.
- v3 uses a single **`operation(name)`** for generic manual work; semantic category defaults to `INTERNAL` at the implementation layer.
- Transport-specific semconv is expressed through **`manual().transport()...`**, not parallel root-level factories.

## Why transport builders are grouped under `transport()`

- Groups HTTP, database, RPC, and Kafka under one namespace, separating **protocol semconv** from generic **application operations**.
- Replaces v1 transport factory methods on `TraceOperations` root interface.
- Enables semconv version markers per transport (`@DatabaseSemconvVersion`, etc.) without polluting `operation(name)`.

## Consequences

### Positive

- Minimal public surface; hard to bypass governance accidentally.
- Clear documentation story: auto by default, `manual()` when needed.
- Facade decorator anti-pattern eliminated.

### Negative

- Breaking migration for any v1 call sites (acceptable — pre-production).
- Developers must learn transport builder nesting.

## References

- [platform-tracing-v3-getting-started.md](../tracing/platform-tracing-v3-getting-started.md)
- [platform-tracing-v3-migration-guide.md](../tracing/platform-tracing-v3-migration-guide.md)
- [R01.md](../known-issues/R01.md)
- [ADR-platform-tracing-micrometer-observation-boundary.md](./ADR-platform-tracing-micrometer-observation-boundary.md)
