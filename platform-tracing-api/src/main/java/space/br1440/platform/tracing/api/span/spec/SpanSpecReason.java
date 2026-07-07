package space.br1440.platform.tracing.api.span.spec;

/**
 * Mandatory governance reason for governed {@code spanFromSpec} usage via {@link SpanSpec}.
 * <p>
 * Generic catch-all values ({@code OTHER}, {@code UNKNOWN}, {@code CUSTOM}, {@code MISC}) are forbidden.
 */
public enum SpanSpecReason {
    UNSUPPORTED_PROTOCOL,
    UNSUPPORTED_LIBRARY,
    LEGACY_INTEGRATION,
    PLATFORM_EDGE_CASE,
    TEMPORARY_WORKAROUND
}
