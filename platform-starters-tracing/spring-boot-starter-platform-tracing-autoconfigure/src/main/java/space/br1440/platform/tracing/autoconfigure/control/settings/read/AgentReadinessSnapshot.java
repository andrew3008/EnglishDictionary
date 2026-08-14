package space.br1440.platform.tracing.autoconfigure.control.settings.read;

public record AgentReadinessSnapshot(String runtimeInstanceId,
                                 long acceptedSequence,
                                 boolean agentReady) {
}
