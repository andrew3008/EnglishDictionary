package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public record ValidationObservation(String agentInstanceId, JmxOwnerState<ValidationValues> owner) {
}
