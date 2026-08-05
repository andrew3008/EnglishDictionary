package space.br1440.platform.tracing.autoconfigure.control.policy.read;

public record AcceptedPolicyProjection(AgentReadinessView agentReadiness,
                                       AcceptedPolicyLeaves policy) {
}
