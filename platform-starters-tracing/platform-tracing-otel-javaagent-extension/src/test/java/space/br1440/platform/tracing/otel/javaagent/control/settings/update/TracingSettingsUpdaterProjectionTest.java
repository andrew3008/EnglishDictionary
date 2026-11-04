package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class TracingSettingsUpdaterProjectionTest {

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void diagnosticsLevelFailureKeepsAppliedOutcome() {
        // given
        TracingSettings initial = TracingSettingsSource.defaultPolicy();
        TracingSettings candidate = TracingSettings.of(
                initial.samplingEnabled(),
                initial.samplingRatio(),
                initial.samplingRouteRatios(),
                initial.validationLevel(),
                initial.exporterEnabled(),
                initial.traceControlHeadersEnabled(),
                AgentLogLevel.DEBUG
        );
        TracingSettingsUpdater updater = new TracingSettingsUpdater(
                AcceptedPolicyTestFixtures.AGENT_INSTANCE_ID, initial, level -> {
                    assertThat(level).isEqualTo(AgentLogLevel.DEBUG);
                    throw new IllegalStateException("diagnostics level rejected");
                });
        TracingSettingsRevision before = updater.current();
        String attemptId = UUID.randomUUID().toString();

        // when
        SettingsUpdateResult result = updater.apply(
                new SettingsUpdateAttempt(attemptId, SettingsWriter.CONFIG_SERVER),
                before.base(),
                candidate
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(result.accepted()).isSameAs(updater.current());
        assertThat(updater.current().policy()).isEqualTo(candidate);
        assertThat(updater.current().acceptedSequence()).isEqualTo(before.acceptedSequence() + 1L);
        assertThat(capture.events())
                .extracting(CapturedLogEvent::eventName)
                .containsExactly("DIAGNOSTICS_LOG_LEVEL_APPLY_FAILED", "TRACING_SETTINGS_UPDATE_APPLIED");
    }
}
