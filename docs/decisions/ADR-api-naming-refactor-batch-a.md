# ADR: API Naming Refactor Batch A

Date: 2026-07-11
Status: Accepted

## Context

The tracing API is pre-production and does not require backward compatibility. Batch A removes misleading or legacy public names from the manual tracing API before production hardening.

## Decision

Accepted Batch A renames and removals:

| Old | New |
| --- | --- |
| `Topology` | `SpanRelationship` |
| `SpanTopologySpec` | `SpanRelationshipSpec` |
| `ImmutableSpanTopologySpec` | `ImmutableSpanRelationshipSpec` |
| `SpanTopologySpec.topology()` | `SpanRelationshipSpec.kind()` |
| `SpanSpec.options()` | `SpanSpec.relationship()` |
| `PlatformSpanBuilder` | `ManualSpanBuilder` |
| `SpecifiedSpan` | `SpanExecution` |
| `SpecifiedSpanImpl` | `SpanExecutionImpl` |
| `EnrichScope` | `SpanEnrichment` |
| `GenericEnrichScope` | `GenericSpanEnrichment` |
| `ValidationMode` | `SemconvValidationMode` |
| `DatabaseTracing` | merged into `DatabaseSpanBuilder` |
| public `SpanScope` | removed from API |

`SpanRelationshipSpec.kind()` describes parent/context relationship only. It is not OpenTelemetry `SpanKind`; protocol kind is still derived from `SpanCategory`.

## Consequences

No aliases, bridges, or deprecated compatibility types are kept. Internal span lifecycle mutation is hidden behind core runtime code and public callers use `SpanHandle`.
