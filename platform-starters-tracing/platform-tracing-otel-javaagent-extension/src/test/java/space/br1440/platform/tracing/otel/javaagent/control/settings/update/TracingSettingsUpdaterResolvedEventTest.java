package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ControlPlaneLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class TracingSettingsUpdaterResolvedEventTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.parse("00000000-0000-0000-0000-000000000202");

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void eachOutcomeUsesItsOwnFixedLevelEvent() {
        // given
        TracingSettings initial = settings(0.5d, true);
        TracingSettingsUpdater updater = new TracingSettingsUpdater(AGENT_INSTANCE_ID, initial, level -> { });
        TracingSettings applied = settings(0.2d, true);

        // when
        updater.apply(attempt(), updater.current().base(), applied);
        updater.apply(attempt(), updater.current().base(), applied);
        updater.apply(attempt(), staleBase(updater.current()), applied);
        updater.apply(
                new SettingsUpdateAttempt(UUID.randomUUID().toString(), SettingsWriter.ACTUATOR),
                updater.current().base(),
                settings(0.1d, false)
        );

        // then
        assertResolved(ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_APPLIED, "APPLIED");
        assertResolved(ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_UNCHANGED, "UNCHANGED");
        assertResolved(ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_REJECTED, "STALE");
        assertResolved(ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_REJECTED, "REJECTED");
    }

    private void assertResolved(AgentLogEventType eventType, String outcome) {
        assertThat(capture.events())
                .filteredOn(event -> eventType.name().equals(event.eventName()))
                .filteredOn(event -> outcome.equals(event.fields().get("outcome")))
                .singleElement()
                .satisfies(event -> assertThat(event.level()).isEqualTo(eventType.level()));
    }

    private static SettingsUpdateAttempt attempt() {
        return new SettingsUpdateAttempt(UUID.randomUUID().toString(), SettingsWriter.CONFIG_SERVER);
    }

    private static SettingsRevision staleBase(TracingSettingsRevision current) {
        return new SettingsRevision(current.agentInstanceId(), current.acceptedSequence() + 1L);
    }

    private static TracingSettings settings(double samplingRatio, boolean exporterEnabled) {
        return TracingSettings.of(
                true,
                samplingRatio,
                Map.of(),
                SpanAttributeValidationLevel.WARNING,
                exporterEnabled,
                true,
                AgentLogLevel.INFO
        );
    }
}
