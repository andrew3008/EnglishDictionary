package space.br1440.platform.tracing.autoconfigure.control.settings;

import space.br1440.platform.tracing.autoconfigure.agent.AgentInstanceIdValidator;

public record SettingsRevision(String agentInstanceId, long acceptedSequence) {

    public SettingsRevision {
        agentInstanceId = AgentInstanceIdValidator.requireCanonical(agentInstanceId, "agentInstanceId");

        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must be >= 0, got " + acceptedSequence);
        }
    }
}
