# ADR: Rename PlatformTracing to TraceOperations

## Status

Accepted

## Context

The root public API interface `PlatformTracing` was rejected by architecture review because the name does not communicate whether the type is runtime, SDK, facade, manual tracing API, platform subsystem, or Spring bean.

The interface exposes two application-facing capabilities:
- read-only access to the active trace context through `traceContext()`;
- manual span creation/enrichment through `manual()`.

It is not the tracing runtime, not the whole SDK, not an OpenTelemetry extension, not autoconfiguration, not an exporter, not a sampler, and not a Spring-specific contract.

## Decision

Rename `PlatformTracing` to `TraceOperations`.

Also rename direct implementations and Spring bean:
- `DefaultPlatformTracing` -> `DefaultTraceOperations`;
- `NoOpPlatformTracing` -> `NoopTraceOperations`;
- `platformTracing` bean -> `traceOperations`.

No compatibility aliases.
No deprecated bridges.

## Rationale

`TraceOperations` follows the Spring-style `XxxOperations` pattern for application-facing contracts. It describes operations over the trace domain rather than platform ownership or runtime infrastructure.

The name is consistent with the cleaned API vocabulary:
- `ActiveTraceContextView`;
- `RequestTraceContextSnapshot`;
- `InboundTraceControl`;
- `TraceControlHeaderInjector`;
- `TraceparentParser`.

The name covers both current capabilities without implying runtime, SDK, platform subsystem, or manual-only scope.

## Consequences

This is a hard pre-production API break.

Application code must inject `TraceOperations` instead of `PlatformTracing`.

The package, artifact names, module names, configuration properties, wire protocol keys, and unrelated internal `Platform*` classes remain unchanged.

## Out of Scope

Do not rename:
- package `space.br1440.platform.tracing`;
- Gradle modules/artifacts;
- `platform.tracing.*` configuration properties;
- `platform-trace-control` SPI name;
- `PlatformTracingAutoConfiguration`;
- `PlatformContextPropagation`;
- `PlatformHeaders`;
- `PlatformTraceContextKeys`;
- `PlatformResourceProvider`;
- `PlatformSpanProcessorFactory`;
- `PlatformEnvironment`;
- `SpanAttributeScrubbingRule`;
- `BuiltInSpanAttributeScrubbingRules`;
- `InboundTraceControl`;
- `OutboundPropagationDecision`;
- `TraceControlHeaderInjector`;
- `TraceparentParser`;
- `ManualTracing`;
- `ManualSpanBuilder`;
- `SpanExecution`;
- `SpanRelationship`;
- `SpanRelationshipSpec`;
- `SpanEnrichment`;
- `GenericSpanEnrichment`;
- `RemoteSpanLink`;
- `SpanSpecAttributeValue`;
- `TracingControlProtocolFieldType`;
- `TracingControlProtocolKeys`;
- `SpanCategory`;
- `SpanResult`.
