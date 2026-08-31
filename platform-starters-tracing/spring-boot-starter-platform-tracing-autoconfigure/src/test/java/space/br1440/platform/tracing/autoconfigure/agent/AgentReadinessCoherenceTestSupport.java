package space.br1440.platform.tracing.autoconfigure.agent;

public final class AgentReadinessCoherenceTestSupport {

    private AgentReadinessCoherenceTestSupport() {
    }

    public static void pinCurrent(AgentReadinessCoherenceGuard guard) {
        AgentReadinessProbe.ProbeResult result = guard.readSnapshot();
        if (result instanceof AgentReadinessProbe.ProbeResult.Present present) {
            guard.pin(present.snapshot());
            return;
        }

        throw new IllegalStateException("Cannot pin readiness test snapshot: " + result);
    }
}
