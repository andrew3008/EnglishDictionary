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

## Breaking Changes - Batch C Naming Cleanup

| Old | New |
| --- | --- |
| `SpanLinkContext` | `RemoteSpanLink` |
| `SpanAttributeValue` | `SpanSpecAttributeValue` |
| `TracingControlProtocolTypes` | `TracingControlProtocolFieldType` |

`TracingControlProtocolFieldType` is a Java type rename only. Enum constants such as
`STRING`, `DOUBLE`, `STRING_ARRAY`, and `ROUTE_RATIOS_MAP` are unchanged, so schema
expected-type strings and wire key names remain unchanged.

## Breaking Changes - PR-B2 Scrubbing SPI Rename

| Old | New |
| --- | --- |
| `SensitiveDataRule` | `SpanAttributeScrubbingRule` |
| `META-INF/services/space.br1440.platform.tracing.api.spi.SensitiveDataRule` | `META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule` |

The SPI still evaluates span attribute key/value pairs only. Built-in and custom implementation
class names are unchanged. External custom rule JARs must be rebuilt against the new SPI and ship
the new ServiceLoader descriptor path.

## Breaking Changes - PR-B1 Context and Propagation Naming

| Old | New |
| --- | --- |
| `TracingRequestContext` | `RequestTraceContextSnapshot` |
| `TraceContextView` | `ActiveTraceContextView` |
| `PlatformTraceControl` | `InboundTraceControl` |
| `PlatformPropagationDecision` | `OutboundPropagationDecision` |
| `PlatformOutboundInjector` | `TraceControlHeaderInjector` |
| `RemoteContext` | moved to `api.propagation` and renamed `TraceparentParser` |
| `fromRemoteContext(...)` | `fromTraceparent(...)` |

No compatibility aliases or deprecated bridges are provided.
