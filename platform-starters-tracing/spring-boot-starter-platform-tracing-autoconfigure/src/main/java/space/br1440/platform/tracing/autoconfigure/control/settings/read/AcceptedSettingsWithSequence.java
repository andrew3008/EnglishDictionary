package space.br1440.platform.tracing.autoconfigure.control.settings.read;

import java.util.Objects;

import space.br1440.platform.tracing.autoconfigure.agent.AgentInstanceIdValidator;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;

public record AcceptedSettingsWithSequence(long acceptedSequence,
                                           String agentInstanceId,
                                           CompleteTracingSettings snapshot) {

    public AcceptedSettingsWithSequence {
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must be greater than or equal to zero");
        }

        AgentInstanceIdValidator.requireCanonical(agentInstanceId, "agentInstanceId");
        Objects.requireNonNull(snapshot, "snapshot");
    }

    public ExpectedSettingsRevision expectedBase() {
        return new ExpectedSettingsRevision(agentInstanceId, acceptedSequence);
    }
}
