package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class AcceptedPolicyTestFixtures {

    static final String RUNTIME_INSTANCE_ID = "00000000-0000-0000-0000-000000000001";

    private AcceptedPolicyTestFixtures() {
    }

    static EffectiveTracingPolicy policy(double samplingRatio) {
        return EffectiveTracingPolicy.of(
                true,
                samplingRatio,
                Map.of("/orders", 0.5d, "/health", 0.0d),
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);
    }

    static PolicyApplyAttempt attempt(long ordinal) {
        return new PolicyApplyAttempt(
                new UUID(0L, ordinal).toString(),
                Instant.parse("2026-07-28T10:15:30Z").plusSeconds(ordinal),
                PolicySourceAuthority.CONFIG_SERVER,
                PolicyApplyTrigger.REFRESH,
                "revision-" + ordinal);
    }
}
