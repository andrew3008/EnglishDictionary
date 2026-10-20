package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.Map;
import java.util.UUID;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AcceptedPolicyTestFixtures {

    public static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.parse("00000000-0000-0000-0000-000000000001");

    public static TracingSettingsStore bootstrapAgentDefaultsStore(AgentInstanceId agentInstanceId,
                                                                   TracingSettings policy) {
        return new TracingSettingsStore(agentInstanceId, policy);
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
                SettingsWriter.CONFIG_SERVER
        );
    }
}
