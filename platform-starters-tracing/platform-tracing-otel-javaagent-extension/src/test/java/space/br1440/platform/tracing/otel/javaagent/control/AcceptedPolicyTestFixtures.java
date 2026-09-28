package space.br1440.platform.tracing.otel.javaagent.control;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public final class AcceptedPolicyTestFixtures {

    public static final AgentInstanceId AGENT_INSTANCE_ID =
            AgentInstanceId.parse("00000000-0000-0000-0000-000000000001");

    private AcceptedPolicyTestFixtures() {
    }

    static TracingSettings policy(double samplingRatio) {
        return TracingSettings.of(
                true,
                samplingRatio,
                Map.of("/orders", 0.5d, "/health", 0.0d),
                SpanAttributeValidationLevel.WARNING,
                true,
                true,
                AgentLogLevel.INFO
        );
    }

    static SettingsUpdateAttempt attempt(long ordinal) {
        return new SettingsUpdateAttempt(
                new UUID(0L, ordinal).toString(),
                Instant.parse("2026-07-28T10:15:30Z").plusSeconds(ordinal),
                SettingsSourceAuthority.CONFIG_SERVER,
                SettingsUpdateTrigger.REFRESH,
                "revision-" + ordinal
        );
    }
}
