package space.br1440.platform.tracing.otel.javaagent.logging;

public enum EmitResult {
    EMITTED,
    FILTERED_BY_LEVEL,
    RATE_LIMITED,
    REJECTED_BY_SECURITY_POLICY,
    DELIVERY_FAILED
}
