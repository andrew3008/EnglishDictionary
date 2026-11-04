package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class AcceptedPolicyCoordinatorTest {

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void exposesAllApplyOutcomesWithoutHiddenRetry() throws Exception {
        // given
        TracingSettingsUpdater updater = updater();

        SettingsRevision initialBase = updater.current().base();

        // when
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
        SettingsUpdateResult rejected = updater.rejectBeforeApply(SettingsRejectionReason.INVALID_VALUE);

        SettingsUpdateAttempt failedAttempt = AcceptedPolicyTestFixtures.attempt(5L);
        setAcceptedSequence(updater, Long.MAX_VALUE);
        SettingsUpdateResult failed = updater.apply(
                failedAttempt,
                updater.current().base(),
                AcceptedPolicyTestFixtures.policy(0.4d));

        // then
        assertThat(applied.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(applied.accepted().acceptedSequence()).isEqualTo(1L);
        assertThat(unchanged.outcome()).isEqualTo(SettingsUpdateStatus.UNCHANGED);
        assertThat(unchanged.accepted()).isSameAs(applied.accepted());
        assertThat(stale.outcome()).isEqualTo(SettingsUpdateStatus.STALE);
        assertThat(stale.messageCode())
                .isEqualTo(SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH);
        assertThat(stale.accepted()).isSameAs(applied.accepted());
        assertThat(rejected.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(rejected.messageCode())
                .isEqualTo(SettingsRejectionReason.INVALID_VALUE);
        assertThat(rejected.accepted()).isSameAs(applied.accepted());
        assertThat(failed.outcome()).isEqualTo(SettingsUpdateStatus.FAILED);
        assertThat(failed.messageCode()).isEqualTo(SettingsRejectionReason.INTERNAL_FAILURE);
        assertThat(failed.accepted()).isSameAs(updater.current());
        assertThat(capture.events())
                .filteredOn(event -> event.throwableDigest() != null)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                    assertThat(event.eventCode()).isEqualTo(AgentEventCode.CTRL_UPDATE);
                    assertThat(event.fields()).containsEntry("attemptId", failedAttempt.attemptId());
                    assertThat(event.throwableDigest()).isNotNull();
                });
    }

    @Test
    void samePolicyWithConfigServerAuthorityIsApplied() {
        // given
        TracingSettingsUpdater updater = updater();
        TracingSettings policy = updater.current().policy();

        // when
        SettingsUpdateResult applied = updater.apply(
                AcceptedPolicyTestFixtures.attempt(1L),
                updater.current().base(),
                policy);

        // then
        assertThat(applied.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(applied.accepted().acceptedSequence()).isEqualTo(1L);
        assertThat(applied.accepted().policy()).isEqualTo(policy);
        assertThat(updater.current().sourceAuthority()).isEqualTo(SettingsSourceAuthority.CONFIG_SERVER);
    }

    @Test
    void agentInstanceMismatchIsReportedSeparately() {
        // given
        TracingSettingsUpdater updater = updater();
        TracingSettingsRevision before = updater.current();

        // when
        SettingsUpdateResult result = updater.apply(
                AcceptedPolicyTestFixtures.attempt(1L),
                new SettingsRevision(
                        AgentInstanceId.parse("00000000-0000-0000-0000-000000000002"),
                        0L
                ),
                AcceptedPolicyTestFixtures.policy(0.2d));

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(result.messageCode()).isEqualTo(SettingsRejectionReason.AGENT_INSTANCE_MISMATCH);
        assertThat(result.accepted()).isSameAs(before);
        assertThat(updater.current()).isSameAs(before);
    }

    private static TracingSettingsUpdater updater() {
        return AcceptedPolicyTestFixtures.bootstrapUpdater(
                AcceptedPolicyTestFixtures.AGENT_INSTANCE_ID,
                AcceptedPolicyTestFixtures.policy(0.1d)
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static void setAcceptedSequence(TracingSettingsUpdater updater,
                                            long acceptedSequence) throws Exception {
        Field currentField = TracingSettingsUpdater.class.getDeclaredField("current");
        currentField.setAccessible(true);

        TracingSettingsRevision previous = updater.current();
        currentField.set(
                updater,
                new TracingSettingsRevision(
                        previous.agentInstanceId(),
                        acceptedSequence,
                        previous.policy(),
                        previous.sourceAuthority()
                )
        );
    }
}
