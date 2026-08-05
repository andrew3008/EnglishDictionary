package space.br1440.platform.tracing.autoconfigure.control.policy;

public record AgentReadinessView(String runtimeInstanceId,
                                 long acceptedSequence,
                                 boolean agentReady) {
}
