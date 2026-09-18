package space.br1440.platform.tracing.otel.javaagent.control;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class AcceptedPolicyTestFixtures {

    public static final String RUNTIME_INSTANCE_ID = "00000000-0000-0000-0000-000000000001";

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
                TracingLogLevel.INFO
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
