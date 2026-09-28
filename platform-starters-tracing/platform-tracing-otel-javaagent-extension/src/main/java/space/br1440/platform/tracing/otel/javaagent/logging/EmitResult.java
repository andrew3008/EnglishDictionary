package space.br1440.platform.tracing.otel.javaagent.logging;

enum EmitResult {
    EMITTED,
    FILTERED_BY_LEVEL,
    RATE_LIMITED,
    DELIVERY_FAILED,
    FALLBACK_EMITTED
}
