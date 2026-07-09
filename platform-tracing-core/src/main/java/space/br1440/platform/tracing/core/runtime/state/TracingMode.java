package space.br1440.platform.tracing.core.runtime.state;

/**
 * Internal supportability mode for platform tracing (Slice 2).
 */
public enum TracingMode {
    ENABLED,
    DISABLED_BY_CONFIGURATION,
    UNAVAILABLE,
    NOOP,
    TEST
}
