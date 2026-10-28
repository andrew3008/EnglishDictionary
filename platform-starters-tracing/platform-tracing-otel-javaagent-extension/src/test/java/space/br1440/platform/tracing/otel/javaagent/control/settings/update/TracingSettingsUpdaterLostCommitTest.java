package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class TracingSettingsUpdaterLostCommitTest {

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void lostCommitIsInternalFailureAndDoesNotApplyDiagnosticsLevel() {
        // given
        TracingSettingsStore store = store();
        TracingSettingsRevision before = store.current();
        AtomicInteger appliedLevels = new AtomicInteger();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(
                store::current,
                (expectedBase, candidate, authority) -> new SettingsCommitResult(
                        SettingsCommitOutcome.STALE,
                        before
                ),
                level -> appliedLevels.incrementAndGet()
        );
        String attemptId = UUID.randomUUID().toString();

        // when
        SettingsUpdateResult result = updater.apply(
                new SettingsUpdateAttempt(attemptId, SettingsWriter.CONFIG_SERVER),
                before.base(),
                withRatio(before.policy(), 0.2d)
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(store.current()).isSameAs(before);
        assertThat(appliedLevels).hasValue(0);
        assertThat(capture.events())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventName()).isEqualTo("TRACING_SETTINGS_UPDATE_COMMIT_FAILED");
                    assertThat(event.level()).isEqualTo(AgentLogLevel.ERROR);
                    assertThat(event.fields())
                            .containsEntry("outcome", "FAILED")
                            .containsEntry("messageCode", "INTERNAL_FAILURE");
                });
    }

    @Test
    void diagnosticsLevelFailureKeepsAppliedOutcome() {
        // given
        TracingSettingsStore store = store();
        TracingSettingsRevision before = store.current();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> {
            throw new IllegalStateException("diagnostics level rejected");
        });
        String attemptId = UUID.randomUUID().toString();

        // when
        SettingsUpdateResult result = updater.apply(
                new SettingsUpdateAttempt(attemptId, SettingsWriter.CONFIG_SERVER),
                before.base(),
                withRatio(before.policy(), 0.2d)
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(store.current().acceptedSequence()).isEqualTo(before.acceptedSequence() + 1L);
        assertThat(capture.events())
                .extracting(CapturedLogEvent::eventName)
                .containsExactly("DIAGNOSTICS_LOG_LEVEL_APPLY_FAILED", "TRACING_SETTINGS_UPDATE_APPLIED");
    }

    private static TracingSettingsStore store() {
        return AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(
                AcceptedPolicyTestFixtures.AGENT_INSTANCE_ID,
                policy()
        );
    }

    private static TracingSettings policy() {
        return TracingSettings.of(
                true,
                1.0d,
                Map.of(),
                SpanAttributeValidationLevel.WARNING,
                true,
                true,
                AgentLogLevel.INFO
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static TracingSettings withRatio(TracingSettings current, double ratio) {
        return TracingSettings.of(
                current.samplingEnabled(),
                ratio,
                current.samplingRouteRatios(),
                current.validationLevel(),
                current.exporterEnabled(),
                current.traceControlHeadersEnabled(),
                current.diagnosticsLogLevel()
        );
    }
}
