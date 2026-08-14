package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

final class JmxContractViolationException extends RuntimeException {

    private final JmxDiagnosticKind kind;

    JmxContractViolationException(JmxDiagnosticKind kind) {
        super(java.util.Objects.requireNonNull(kind, "kind").name(), null, false, false);
        this.kind = kind;
    }

    JmxDiagnosticKind kind() {
        return kind;
    }
}
