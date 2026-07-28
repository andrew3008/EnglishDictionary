package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Secret-safe public Actuator read projection assembled from three independent truth categories.
 * Route ratio values are not present. Only count is exposed.
 */
public record AcceptedPolicyProjection(
        AgentReadinessView agentReadiness,
        AcceptedPolicyLeaves policy,
        TracingApplyDiagnostics diagnostics
) {}
