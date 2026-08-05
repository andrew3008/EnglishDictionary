package space.br1440.platform.tracing.autoconfigure.control.policy.read;

public record AgentReadinessView(String runtimeInstanceId,
                                 long acceptedSequence,
                                 boolean agentReady) {
}
