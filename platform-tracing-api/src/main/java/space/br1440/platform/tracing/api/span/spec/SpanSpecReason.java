package space.br1440.platform.tracing.api.span.spec;

public enum SpanSpecReason {
    UNSUPPORTED_PROTOCOL,
    UNSUPPORTED_LIBRARY,
    LEGACY_INTEGRATION,
    PLATFORM_EDGE_CASE,
    TEMPORARY_WORKAROUND
}
