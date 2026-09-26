package space.br1440.platform.tracing.otel.javaagent.control.settings.model;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

public record ExpectedSettingsRevision(AgentInstanceId agentInstanceId, long acceptedSequence) {

    public ExpectedSettingsRevision {
        Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("AcceptedSequence must not be negative");
        }
    }
}
