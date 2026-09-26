package space.br1440.platform.tracing.autoconfigure.control.settings;

import space.br1440.platform.tracing.autoconfigure.agent.AgentInstanceIdValidator;

public record ExpectedSettingsRevision(String agentInstanceId, long acceptedSequence) {

    public ExpectedSettingsRevision {
        agentInstanceId = AgentInstanceIdValidator.requireCanonical(agentInstanceId, "agentInstanceId");

        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must be >= 0, got " + acceptedSequence);
        }
    }
}
