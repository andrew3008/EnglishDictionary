package space.br1440.platform.tracing.autoconfigure.agent;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AgentReadinessCoherenceTestSupport {

    public static void pinCurrent(AgentReadinessCoherenceGuard guard) {
        AgentReadinessProbe.ProbeResult result = guard.readSnapshot();
        if (result instanceof AgentReadinessProbe.ProbeResult.Present(AgentReadinessSnapshot snapshot)) {
            guard.pin(snapshot);
            return;
        }

        throw new IllegalStateException("Cannot pin readiness test snapshot: " + result);
    }
}
