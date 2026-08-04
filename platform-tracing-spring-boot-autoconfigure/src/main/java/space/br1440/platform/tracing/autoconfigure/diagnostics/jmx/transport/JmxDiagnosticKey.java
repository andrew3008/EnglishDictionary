package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.util.Objects;

public record JmxDiagnosticKey(JmxContractPoint point, JmxDiagnosticCategory category, JmxDiagnosticKind kind) {

    public JmxDiagnosticKey {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(kind, "kind");
    }

}
