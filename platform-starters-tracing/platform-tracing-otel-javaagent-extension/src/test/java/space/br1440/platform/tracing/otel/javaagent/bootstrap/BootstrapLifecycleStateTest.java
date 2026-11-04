package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.PLATFORM_ACP_NOT_EXECUTED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.REENTRANT_OR_OUT_OF_ORDER;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapLifecycleStateTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void repeatedFailureKeepsTheFirstFailureWithoutLogging() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException firstCause = new IllegalStateException("initial failure");
        UnsupportedOperationException rejectedCause = new UnsupportedOperationException("subsequent failure");

        // when
        FailureAdmission initial = lifecycle.fail("INITIAL_FAILURE", "prepare", firstCause);
        FailureAdmission repeated = lifecycle.fail("SUBSEQUENT_FAILURE", "activate", rejectedCause);

        // then
        assertThat(initial.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(repeated.kind()).isEqualTo(FailureAdmission.Kind.ALREADY_FAILED);
        assertThat(repeated.failure()).isSameAs(initial.failure());
        assertThat(repeated.failure().primaryCode()).isEqualTo("INITIAL_FAILURE");
        assertThat(repeated.failure().effectiveCode()).isEqualTo("INITIAL_FAILURE");
        assertThat(repeated.failure().cause()).isSameAs(firstCause);
        assertThat(rejectedCause.getSuppressed()).isEmpty();
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void rollbackEscalationIsIdempotent() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException cleanupFailure = new IllegalStateException("Initial cleanup failure");

        // when
        FailureAdmission escalated = lifecycle.escalateRollbackIncomplete("close", cleanupFailure);
        FailureAdmission repeated = lifecycle.escalateRollbackIncomplete("shutdown", cleanupFailure);

        // then
        assertThat(escalated.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(repeated.kind()).isEqualTo(FailureAdmission.Kind.ALREADY_FAILED);
        assertThat(repeated.failure()).isSameAs(escalated.failure()).isSameAs(lifecycle.failure());
        assertThat(repeated.failure().primaryCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.failure().cause()).isSameAs(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void ordinaryFailureAfterRollbackEscalationKeepsEffectiveCode() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        UnsupportedOperationException cleanupFailure = new UnsupportedOperationException("Cleanup failure");
        IllegalArgumentException subsequentCause = new IllegalArgumentException("Subsequent failure");
        lifecycle.fail("ACTIVATION_FAILED", "activate", primaryCause);

        // when
        FailureAdmission escalation = lifecycle.escalateRollbackIncomplete("rollback", cleanupFailure);
        FailureAdmission repeated = lifecycle.fail("SUBSEQUENT_FAILURE", "close", subsequentCause);

        // then
        assertThat(escalation.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(escalation.claimedSession()).isNull();
        assertThat(repeated.kind()).isEqualTo(FailureAdmission.Kind.ALREADY_FAILED);
        assertThat(repeated.failure().primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(repeated.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.failure().phase()).isEqualTo("rollback");
        assertThat(repeated.failure().cause()).isSameAs(primaryCause);
        assertThat(subsequentCause.getSuppressed()).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void rollbackEscalationDoesNotMutateCauseGraph() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        LinkageError cleanupFailure = new LinkageError("Fatal cleanup failure");
        lifecycle.fail("ACTIVATION_FAILED", "activate", primaryCause);

        // when
        lifecycle.escalateRollbackIncomplete("rollback", cleanupFailure);

        // then
        BootstrapFailure failure = lifecycle.failure();
        assertThat(failure.primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(failure.effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failure.cause()).isSameAs(primaryCause);
        assertThat(primaryCause.getSuppressed()).isEmpty();
        assertThat(cleanupFailure.getSuppressed()).isEmpty();
    }

    @Test
    void rollbackEscalationWithPrimaryCauseDoesNotSelfSuppress() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        lifecycle.fail("ACTIVATION_FAILED", "activate", primaryCause);

        // when
        lifecycle.escalateRollbackIncomplete("rollback", primaryCause);

        // then
        BootstrapFailure failure = lifecycle.failure();
        assertThat(failure.primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(failure.effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failure.cause()).isSameAs(primaryCause);
        assertThat(primaryCause.getSuppressed()).isEmpty();
    }

    @Test
    void rollbackEscalationFromClosedIsRejectedWithoutStateChange() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
        lifecycle.markClosed();
        lifecycle.completeClose(acquisition.flight(), null);
        IllegalStateException cleanupFailure = new IllegalStateException("Late cleanup failure");

        // when
        FailureAdmission escalation = lifecycle.escalateRollbackIncomplete("close", cleanupFailure);

        // then
        assertThat(escalation.kind()).isEqualTo(FailureAdmission.Kind.REJECTED);
        assertThat(escalation.failure().primaryCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(escalation.failure().phase()).isEqualTo("close");
        assertThat(escalation.failure().cause()).isSameAs(cleanupFailure);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        assertThat(lifecycle.diagnosticSnapshot().failure()).isNull();
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void failureDuringCloseIsRejectedWithoutStateChange() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
        IllegalStateException cause = new IllegalStateException("Late customization failure");

        // when
        FailureAdmission admission = lifecycle.fail("LATE_FAILURE", "customization", cause);
        lifecycle.markClosed();
        lifecycle.completeClose(acquisition.flight(), null);

        // then
        assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.REJECTED);
        assertThat(admission.failure().primaryCode()).isEqualTo("LATE_FAILURE");
        assertThat(admission.failure().cause()).isSameAs(cause);
        assertThat(admission.claimedSession()).isNull();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        assertThat(lifecycle.diagnosticSnapshot().failure()).isNull();
    }

    @Test
    void firstCloseFromFailedOwnsResourcesAndReplaysItsOutcome() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapResources resources = mock(BootstrapResources.class);
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        IllegalStateException cleanupFailure = new IllegalStateException("Cleanup failure");
        lifecycle.attachResources(resources);
        FailureAdmission admission = lifecycle.fail("ACTIVATION_FAILED", "activate", primaryCause);

        // when
        BootstrapLifecycleState.CloseAcquisition first = lifecycle.acquireClose();
        lifecycle.markClosed();
        lifecycle.completeClose(first.flight(), cleanupFailure);
        BootstrapLifecycleState.CloseAcquisition repeated = lifecycle.acquireClose();

        // then
        assertThat(admission.claimedSession()).isNull();
        assertThat(first.owner()).isTrue();
        assertThat(first.reentrant()).isFalse();
        assertThat(first.context().resources()).isSameAs(resources);
        assertThat(first.context().failureBeforeClose()).isSameAs(lifecycle.failure());
        assertThat(repeated.owner()).isFalse();
        assertThat(repeated.reentrant()).isFalse();
        assertThat(repeated.awaitOutcome()).isSameAs(cleanupFailure);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(lifecycle.failure().cause()).isSameAs(primaryCause);
    }

    @Test
    void closeFlightSharesExactOutcomeWithFollowerAndCompletedReplay() throws InterruptedException {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapLifecycleState.CloseAcquisition owner = lifecycle.acquireClose();
        IllegalStateException closeFailure = new IllegalStateException("Close failure");
        AtomicReference<BootstrapLifecycleState.CloseAcquisition> followerAcquisition = new AtomicReference<>();
        AtomicReference<Throwable> followerOutcome = new AtomicReference<>();
        CountDownLatch followerAcquired = new CountDownLatch(1);
        Thread follower = new Thread(() -> {
            BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
            followerAcquisition.set(acquisition);
            followerAcquired.countDown();
            followerOutcome.set(acquisition.awaitOutcome());
        }, "bootstrap-close-follower");
        follower.setDaemon(true);

        // when
        follower.start();
        try {
            assertThat(followerAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(followerAcquisition.get().owner()).isFalse();
            assertThat(followerAcquisition.get().reentrant()).isFalse();
        } finally {
            lifecycle.completeClose(owner.flight(), closeFailure);
            follower.join(TimeUnit.SECONDS.toMillis(5));
        }
        BootstrapLifecycleState.CloseAcquisition replay = lifecycle.acquireClose();

        // then
        assertThat(follower.isAlive()).isFalse();
        assertThat(owner.awaitOutcome()).isSameAs(closeFailure);
        assertThat(followerOutcome.get()).isSameAs(closeFailure);
        assertThat(replay.owner()).isFalse();
        assertThat(replay.reentrant()).isFalse();
        assertThat(replay.awaitOutcome()).isSameAs(closeFailure);
    }

    @Test
    void closeOwnerReentrancyDoesNotWaitForItsOwnFlight() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapLifecycleState.CloseAcquisition owner = lifecycle.acquireClose();

        // when
        BootstrapLifecycleState.CloseAcquisition reentrant = lifecycle.acquireClose();
        lifecycle.completeClose(owner.flight(), null);
        BootstrapLifecycleState.CloseAcquisition replay = lifecycle.acquireClose();

        // then
        assertThat(owner.owner()).isTrue();
        assertThat(reentrant.owner()).isFalse();
        assertThat(reentrant.reentrant()).isTrue();
        assertThat(reentrant.flight()).isSameAs(owner.flight());
        assertThat(replay.reentrant()).isFalse();
        assertThat(replay.awaitOutcome()).isNull();
    }

    @Test
    void interruptedFollowerWaitsForOutcomeAndRestoresInterruptFlag() throws InterruptedException {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapLifecycleState.CloseAcquisition owner = lifecycle.acquireClose();
        IllegalStateException closeFailure = new IllegalStateException("Close failure");
        AtomicReference<Throwable> followerOutcome = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        CountDownLatch awaitingOutcome = new CountDownLatch(1);
        CountDownLatch followerFinished = new CountDownLatch(1);
        Thread follower = new Thread(() -> {
            BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
            Thread.currentThread().interrupt();
            awaitingOutcome.countDown();
            try {
                followerOutcome.set(acquisition.awaitOutcome());
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                followerFinished.countDown();
            }
        }, "interrupted-bootstrap-close-follower");
        follower.setDaemon(true);

        // when
        follower.start();
        try {
            assertThat(awaitingOutcome.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(followerFinished.getCount()).isEqualTo(1L);
        } finally {
            lifecycle.completeClose(owner.flight(), closeFailure);
            follower.join(TimeUnit.SECONDS.toMillis(5));
        }

        // then
        assertThat(follower.isAlive()).isFalse();
        assertThat(followerOutcome.get()).isSameAs(closeFailure);
        assertThat(interruptRestored).isTrue();
    }

    @Test
    void completePrepareAfterFailureReturnsStickyFailureWithoutBindingSession() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Preparation failure");
        lifecycle.beginPrepare();
        lifecycle.fail("PREPARATION_FAILED", "prepare", primaryCause);

        try (AutoconfigureSession session = mock(AutoconfigureSession.class)) {
            // when
            Throwable thrown = catchThrowable(() -> lifecycle.completePrepare(session));

            // then
            assertThat(thrown).isInstanceOf(BootstrapException.class);
            BootstrapException sticky = (BootstrapException) thrown;
            assertThat(sticky.failure().primaryCode()).isEqualTo("PREPARATION_FAILED");
            assertThat(sticky.failure().effectiveCode()).isEqualTo("PREPARATION_FAILED");
            assertThat(sticky.getCause()).isSameAs(primaryCause);
            assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
            assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        }
    }

    @Test
    void completePrepareAfterCloseRejectsAdmissionWithoutBindingSession() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
        lifecycle.markClosed();
        lifecycle.completeClose(acquisition.flight(), null);

        try (AutoconfigureSession session = mock(AutoconfigureSession.class)) {
            // when
            Throwable thrown = catchThrowable(() -> lifecycle.completePrepare(session));

            // then
            assertThat(thrown).isInstanceOf(BootstrapAdmissionRejectedException.class);
            BootstrapAdmissionRejectedException rejection = (BootstrapAdmissionRejectedException) thrown;
            assertThat(rejection.primaryCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
            assertThat(rejection.failure().effectiveCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
            assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
            assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        }
    }

    @Test
    void reentrantPrepareGuardRejectsAdmissionWithoutTerminalizingLifecycle() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        lifecycle.beginPrepare();

        // when
        Throwable thrown = catchThrowable(lifecycle::beginPrepare);

        // then
        assertThat(thrown).isInstanceOf(BootstrapAdmissionRejectedException.class);
        BootstrapAdmissionRejectedException rejection = (BootstrapAdmissionRejectedException) thrown;
        assertThat(rejection.primaryCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
        assertThat(rejection.failure().phase()).isEqualTo("prepare");
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.PREPARING);
        assertThat(logs.events()).isEmpty();
    }

    @Test
    void activationWithoutPreparationGuardRejectsAdmissionWithoutTerminalizingLifecycle() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();

        // when
        Throwable thrown = catchThrowable(lifecycle::beginActivation);

        // then
        assertThat(thrown).isInstanceOf(BootstrapAdmissionRejectedException.class);
        BootstrapAdmissionRejectedException rejection = (BootstrapAdmissionRejectedException) thrown;
        assertThat(rejection.primaryCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
        assertThat(rejection.failure().phase()).isEqualTo("activate");
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.NEW);
    }

    @Test
    void recorded_failure_claims_prepared_session_once_and_keeps_its_evidence() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = preparedSession(lifecycle);
        ExtensionRuntimeEvidence evidence = session.evidence();
        IllegalStateException customizationFailure = new IllegalStateException("Customization failed");

        // when
        FailureAdmission recorded = lifecycle.fail("CUSTOMIZATION_FAILED", "customization", customizationFailure);
        FailureAdmission repeated = lifecycle.fail("REPEATED_FAILURE", "customization", new IllegalStateException("Repeated"));
        BootstrapLifecycleState.CloseAcquisition close = lifecycle.acquireClose();
        lifecycle.completeClose(close.flight(), null);

        // then
        assertThat(recorded.claimedSession()).isSameAs(session);
        assertThat(repeated.claimedSession()).isNull();
        assertThat(close.context().session()).isNull();
        assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
        assertThat(lifecycle.currentEvidence()).isSameAs(evidence);
    }

    @Test
    void close_owns_session_and_failure_during_close_cannot_claim_it() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = preparedSession(lifecycle);
        ExtensionRuntimeEvidence evidence = session.evidence();
        IllegalStateException customizationFailure = new IllegalStateException("Customization failed");

        // when
        BootstrapLifecycleState.CloseAcquisition close = lifecycle.acquireClose();
        FailureAdmission admission = lifecycle.fail("CUSTOMIZATION_FAILED", "customization", customizationFailure);
        lifecycle.completeClose(close.flight(), null);

        // then
        assertThat(close.context().session()).isSameAs(session);
        assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.REJECTED);
        assertThat(admission.claimedSession()).isNull();
        assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
        assertThat(lifecycle.currentEvidence()).isSameAs(evidence);
    }

    @Test
    void activation_moves_session_to_resources_and_failure_does_not_claim_it() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = preparedSession(lifecycle);
        ExtensionRuntimeEvidence evidence = session.evidence();
        assertThat(lifecycle.beginActivation()).isSameAs(session);
        BootstrapResources resources = new BootstrapResources();
        resources.push(session, "autoconfigure-session");

        // when
        lifecycle.attachResources(resources);
        lifecycle.commitActive();
        FailureAdmission admission = lifecycle.fail(
                "ACTIVATION_FAILED",
                "activate",
                new IllegalStateException("Activation failed")
        );
        BootstrapLifecycleState.CloseAcquisition close = lifecycle.acquireClose();
        resources.close();
        lifecycle.completeClose(close.flight(), null);

        // then
        assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(admission.claimedSession()).isNull();
        assertThat(close.context().resources()).isSameAs(resources);
        assertThat(close.context().session()).isNull();
        assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
        assertThat(lifecycle.currentEvidence()).isSameAs(evidence);
    }

    @Test
    void failPrepared_is_admitted_only_from_prepared_state() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException earlyFailure = new IllegalStateException("Startup failure before prepare");

        // when
        Optional<FailureAdmission> beforePrepare = lifecycle.failPrepared("AGENT_STARTUP_FAILED", "startup", earlyFailure);
        AutoconfigureSession session = preparedSession(lifecycle);
        Optional<FailureAdmission> prepared = lifecycle.failPrepared(
                "AGENT_STARTUP_FAILED",
                "startup",
                new IllegalStateException("Startup failure after prepare")
        );

        // then
        assertThat(beforePrepare).isEmpty();
        assertThat(prepared).hasValueSatisfying(admission -> {
            assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
            assertThat(admission.claimedSession()).isSameAs(session);
            assertThat(admission.failure().phase()).isEqualTo("startup");
        });
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void recordMissingPreparation_is_admitted_only_from_new_state() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException cause = new IllegalStateException("Missing preparation");

        // when
        Optional<FailureAdmission> missing = lifecycle.recordMissingPreparation(cause);
        Optional<FailureAdmission> repeated = lifecycle.recordMissingPreparation(cause);

        // then
        assertThat(missing).hasValueSatisfying(admission -> {
            assertThat(admission.failure().primaryCode()).isEqualTo(PLATFORM_ACP_NOT_EXECUTED);
            assertThat(admission.failure().phase()).isEqualTo("activate");
            assertThat(admission.claimedSession()).isNull();
        });
        assertThat(repeated).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void non_recorded_admission_cannot_own_session_or_projection_failure() {
        // given
        BootstrapFailure failure = new BootstrapFailure("CODE", "CODE", "prepare", new IllegalStateException("Failure"));

        // when/then
        assertThatThrownBy(() -> new FailureAdmission(
                FailureAdmission.Kind.ALREADY_FAILED,
                failure,
                mock(AutoconfigureSession.class),
                null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailureAdmission.rejected(failure)
                .withProjectionFailure(new IllegalStateException("Projection failure")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AutoconfigureSession preparedSession(BootstrapLifecycleState lifecycle) {
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        when(session.evidence()).thenReturn(ExtensionRuntimeEvidence.unavailable("prepared-version"));
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
        return session;
    }
}
