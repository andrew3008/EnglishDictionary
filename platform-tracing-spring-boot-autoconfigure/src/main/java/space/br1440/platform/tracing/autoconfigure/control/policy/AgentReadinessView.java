package space.br1440.platform.tracing.autoconfigure.control.policy;

/**
 * Agent readiness truth category. Separate from policy values and apply diagnostics.
 */
public record AgentReadinessView(
        String runtimeInstanceId,
        long acceptedSequence,
        boolean agentReady
) {}
