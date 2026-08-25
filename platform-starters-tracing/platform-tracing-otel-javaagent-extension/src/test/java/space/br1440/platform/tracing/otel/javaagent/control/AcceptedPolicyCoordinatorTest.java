package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdateSnapshot;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

class AcceptedPolicyCoordinatorTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void exposesAllApplyOutcomesWithoutHiddenRetry() throws Exception {
        TracingSettingsStore store = store();
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(
                store,
                new SettingsValidationConstraints(false),
                diagnostics);

        ExpectedSettingsRevision initialBase = store.current().base();
        SettingsUpdateResult applied = updater.apply(
                AcceptedPolicyTestFixtures.attempt(1L),
                initialBase,
                AcceptedPolicyTestFixtures.policy(0.2d));
        SettingsUpdateResult unchanged = updater.apply(
                AcceptedPolicyTestFixtures.attempt(2L),
                applied.accepted().base(),
                applied.accepted().policy());
        SettingsUpdateResult stale = updater.apply(
                AcceptedPolicyTestFixtures.attempt(3L),
                initialBase,
                AcceptedPolicyTestFixtures.policy(0.3d));
        SettingsUpdateResult rejected = updater.apply(
                AcceptedPolicyTestFixtures.attempt(4L),
                applied.accepted().base(),
                strictPolicy());

        SettingsUpdateAttempt failedAttempt = AcceptedPolicyTestFixtures.attempt(5L);
        setAcceptedSequence(store, Long.MAX_VALUE);
        SettingsUpdateResult failed = updater.apply(
                failedAttempt,
                store.current().base(),
                AcceptedPolicyTestFixtures.policy(0.4d));

        assertThat(applied.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(applied.accepted().acceptedSequence()).isEqualTo(1L);
        assertThat(unchanged.outcome()).isEqualTo(SettingsUpdateStatus.UNCHANGED);
        assertThat(unchanged.accepted()).isSameAs(applied.accepted());
        assertThat(stale.outcome()).isEqualTo(SettingsUpdateStatus.STALE);
        assertThat(stale.messageCode())
                .isEqualTo(SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH);
        assertThat(rejected.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(rejected.messageCode())
                .isEqualTo(SettingsRejectionReason.STRICT_RUNTIME_NOT_ALLOWED);
        assertThat(failed.outcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(failed.messageCode()).isEqualTo(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(capture.events())
                .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventCode()).isEqualTo(AgentEventCodes.CTRL_UPDATE);
                    assertThat(event.fields()).containsEntry("attemptId", failedAttempt.attemptId());
                    assertThat(event.throwableDigest()).isNotNull();
                });

        LastSettingsUpdateSnapshot snapshot = diagnostics.snapshot();
        assertThat(snapshot.outcomeCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                SettingsUpdateStatus.APPLIED, 1L,
                SettingsUpdateStatus.UNCHANGED, 1L,
                SettingsUpdateStatus.STALE, 1L,
                SettingsUpdateStatus.REJECTED, 1L,
                SettingsUpdateStatus.FAILED, 1L));
    }

    @Test
    void runtimeMismatchIsReportedSeparately() {
        TracingSettingsStore store = store();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(
                store,
                new SettingsValidationConstraints(true),
                new LastSettingsUpdate());

        SettingsUpdateResult result = updater.apply(
                AcceptedPolicyTestFixtures.attempt(1L),
                new ExpectedSettingsRevision(
                        "00000000-0000-0000-0000-000000000002",
                        0L),
                AcceptedPolicyTestFixtures.policy(0.2d));

        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.STALE);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.RUNTIME_INSTANCE_MISMATCH);
        assertThat(store.current().acceptedSequence()).isZero();
    }

    private static TracingSettingsStore store() {
        return new TracingSettingsStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                AcceptedPolicyTestFixtures.policy(0.1d));
    }

    private static TracingSettings strictPolicy() {
        return TracingSettings.of(
                true,
                0.2d,
                Map.of(),
                true,
                true,
                true,
                true,
                TracingLogLevel.INFO);
    }

    @SuppressWarnings("unchecked")
    private static void setAcceptedSequence(
            TracingSettingsStore store,
            long acceptedSequence) throws Exception {
        Field currentField = TracingSettingsStore.class.getDeclaredField("current");
        currentField.setAccessible(true);
        AtomicReference<TracingSettingsRevision> current =
                (AtomicReference<TracingSettingsRevision>) currentField.get(store);
        TracingSettingsRevision previous = current.get();
        current.set(new TracingSettingsRevision(
                previous.runtimeInstanceId(),
                acceptedSequence,
                previous.policy()));
    }
}
