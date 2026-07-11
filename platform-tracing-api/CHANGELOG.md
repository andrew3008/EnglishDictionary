# platform-tracing-api Changelog

## Breaking Changes - Pre-Production Rename

| Old | New |
| --- | --- |
| `Topology` | `SpanRelationship` |
| `SpanTopologySpec` | `SpanRelationshipSpec` |
| `ImmutableSpanTopologySpec` | `ImmutableSpanRelationshipSpec` |
| `SpanTopologySpec.topology()` | `SpanRelationshipSpec.kind()` |
| `SpanSpec.options()` | `SpanSpec.relationship()` |
| `PlatformSpanBuilder` | `ManualSpanBuilder` |
| `SpecifiedSpan` | `SpanExecution` |
| `EnrichScope` | `SpanEnrichment` |
| `GenericEnrichScope` | `GenericSpanEnrichment` |
| `ValidationMode` | `SemconvValidationMode` |
| `DatabaseTracing` | merged into `DatabaseSpanBuilder` |
| public `SpanScope` | removed |

No compatibility aliases or deprecated bridges are provided.
