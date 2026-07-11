# ADR: PR-B1 Context and Propagation API Naming

## Status

Accepted - 2026-07-11.

## Context

Batch A established the manual span builder and relationship vocabulary. PR-B1 completes the context and propagation naming slice before production, with no compatibility aliases or deprecated bridges.

## Decision

Use names that encode data lifetime and propagation direction:

| Previous | Current | Rationale |
| --- | --- | --- |
| `TracingRequestContext` | `RequestTraceContextSnapshot` | Nullable captured request/error-handling snapshot. |
| `TraceContextView` | `ActiveTraceContextView` | Read-only live view over the active trace/span context. |
| `PlatformTraceControl` | `InboundTraceControl` | Extracted from inbound trace-control headers. |
| `PlatformPropagationDecision` | `OutboundPropagationDecision` | Result of outbound trusted-destination policy. |
| `PlatformOutboundInjector` | `TraceControlHeaderInjector` | Writes only trace-control headers, not W3C propagation. |
| `RemoteContext` | `TraceparentParser` | Parser utility for W3C `traceparent`, not a parsed value object. |
| `fromRemoteContext(...)` | `fromTraceparent(...)` | Builder input is strict W3C `traceparent` values. |

`TraceparentParser` belongs in `space.br1440.platform.tracing.api.propagation`.

## Consequences

The public API is breaking and pre-production only. Source, tests, docs, service descriptors, and extension SPI checks must use the current names. Historical review documents may still contain old names as archaeology, but current API references should not.
