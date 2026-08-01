package space.br1440.platform.tracing.autoconfigure.control.policy;

/**
 * Secret-safe public Actuator read projection assembled from two independent truth categories.
 * Route ratio values are not present. Only count is exposed.
 */
public record AcceptedPolicyProjection(
        AgentReadinessView agentReadiness,
        AcceptedPolicyLeaves policy
) {}
