# ADR - Delegate W3C traceparent Parsing to OpenTelemetry

| Field | Value |
| --- | --- |
| Status | Accepted |
| Date | 2026-07-14 |
| Context | Refactoring `api.propagation.TraceparentParser` before production API freeze |

## Context

`TraceparentParser` was a hand-written public utility for parsing W3C `traceparent` strings into `RemoteSpanLink`.
It duplicated validation rules owned by OpenTelemetry and exposed a raw wire-parser as public API.

The public ergonomic entry points remain the builder methods:

- `ManualSpanBuilder.fromTraceparent(String...)`
- `SpanSpecBuilder.fromTraceparent(String...)`

Those methods are strict and continue to return span links through existing builder state.

## Decision

Delete `TraceparentParser`.

Add `OtelTraceparentReader` in `space.br1440.platform.tracing.api.propagation` as a narrow OTel-backed bridge from raw W3C `traceparent` values to `RemoteSpanLink`.
It delegates extraction to `io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator`.

`OtelTraceparentReader` is public only for module visibility and tests/samples. It is not extension API; access is restricted with ArchUnit.

No compatibility aliases and no `@Deprecated` bridges are provided.

## Dependency Contract

`platform-tracing-api` keeps `io.opentelemetry:opentelemetry-api` and `io.opentelemetry:opentelemetry-context` as `compileOnly`.
This refactoring does not promote either dependency to `api` and does not add new Gradle dependencies.

Runtime execution of code paths that use OTel-backed traceparent reading requires the consuming runtime to provide OTel API/Context, as already expected for the platform runtime/agent integration.

## Consequences

- W3C validation behavior follows OpenTelemetry's propagator.
- `RemoteSpanLink.traceFlags` remains `byte`.
- `RemoteSpanLink.traceState` remains `null` for single-string `traceparent` parsing, because `tracestate` is a separate W3C header.
- Future two-header `traceparent + tracestate` convenience API remains out of scope.
- Public builder method names and signatures are unchanged.
