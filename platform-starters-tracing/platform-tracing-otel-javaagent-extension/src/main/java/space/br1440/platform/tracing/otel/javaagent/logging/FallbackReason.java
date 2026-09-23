package space.br1440.platform.tracing.otel.javaagent.logging;

enum FallbackReason {

    INVARIANT_VIOLATION("invariant_violation"),
    BYTE_BUDGET("byte_budget");

    private final String wireValue;

    FallbackReason(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }
}
