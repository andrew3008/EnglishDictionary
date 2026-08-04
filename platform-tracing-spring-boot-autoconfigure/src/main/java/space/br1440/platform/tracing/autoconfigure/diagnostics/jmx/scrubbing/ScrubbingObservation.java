package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

public record ScrubbingObservation(String agentInstanceId, JmxOwnerState<ScrubbingValues> owner) {
}
