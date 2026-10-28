package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;

import java.util.List;
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
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapLifecycleStateTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void repeatedFailureKeepsTheFirstFailureAndLogsTheRejectedCause() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException firstCause = new IllegalStateException("initial failure");
        UnsupportedOperationException rejectedCause = new UnsupportedOperationException("subsequent failure");

        // when
        BootstrapException initial = lifecycle.fail("INITIAL_FAILURE", "prepare", "prepare", null, firstCause);
        BootstrapException repeated = lifecycle.fail(
                "SUBSEQUENT_FAILURE",
                "activate",
                "activate",
                null,
                rejectedCause
        );

        // then
        assertThat(repeated.failure()).isSameAs(initial.failure());
        assertThat(repeated.failure().primaryCode()).isEqualTo("INITIAL_FAILURE");
        assertThat(repeated.failure().effectiveCode()).isEqualTo("INITIAL_FAILURE");
        assertThat(repeated.getCause()).isSameAs(firstCause);

        List<CapturedLogEvent> events = logs.events().stream()
                .filter(event -> BootstrapLifecycleState.class.getName().equals(event.logger()))
                .toList();
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().message()).isEqualTo("Bootstrap phase failed");
        assertThat(events.getFirst().fields())
                .containsEntry("failureCode", "INITIAL_FAILURE")
                .containsEntry("phase", "prepare")
                .containsEntry("subphase", "prepare");
        assertThat(events.getLast().message()).isEqualTo("Bootstrap operation rejected");
        assertThat(events.getLast().fields())
                .containsEntry("failureCode", "SUBSEQUENT_FAILURE")
                .containsEntry("phase", "activate")
                .containsEntry("subphase", "activate");
        assertThat(events.getLast().throwableDigest())
                .contains(UnsupportedOperationException.class.getName());
    }

    @Test
    void rollbackEscalationIsIdempotent() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException cleanupFailure = new IllegalStateException("Initial cleanup failure");

        // when
        lifecycle.escalateRollbackIncomplete("close", cleanupFailure);
        BootstrapFailure escalated = lifecycle.failure();
        lifecycle.escalateRollbackIncomplete("shutdown", cleanupFailure);
        BootstrapFailure repeated = lifecycle.failure();

        // then
        assertThat(repeated).isSameAs(escalated);
        assertThat(repeated.primaryCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.cause()).isSameAs(cleanupFailure);
        assertThat(repeated.cause().getSuppressed()).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void ordinaryFailureAfterRollbackEscalationKeepsEffectiveCode() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        UnsupportedOperationException cleanupFailure = new UnsupportedOperationException("Cleanup failure");
        IllegalArgumentException subsequentCause = new IllegalArgumentException("Subsequent failure");
        BootstrapException initial = lifecycle.fail(
                "ACTIVATION_FAILED",
                "activate",
                "activate",
                null,
                primaryCause
        );

        // when
        lifecycle.escalateRollbackIncomplete("rollback", cleanupFailure);
        BootstrapException repeated = lifecycle.fail(
                "SUBSEQUENT_FAILURE",
                "close",
                "close",
                null,
                subsequentCause
        );

        // then
        assertThat(repeated.failure().primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(repeated.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.getCause()).isSameAs(initial.getCause()).isSameAs(primaryCause);
        assertThat(repeated.getCause().getSuppressed()).containsExactly(cleanupFailure);
        assertThat(subsequentCause.getSuppressed()).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void fatalRollbackEscalationKeepsPrimaryCauseAndSuppressesExactCleanupFailure() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        AssertionError cleanupFailure = new AssertionError("Fatal cleanup failure");
        assertThat(lifecycle.fail("ACTIVATION_FAILED", "activate", "activate", null, primaryCause).getCause())
                .isSameAs(primaryCause);

        // when
        lifecycle.escalateRollbackIncomplete("rollback", cleanupFailure);

        // then
        BootstrapFailure failure = lifecycle.failure();
        assertThat(failure.primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(failure.effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failure.cause()).isSameAs(primaryCause);
        assertThat(primaryCause.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).isEmpty();
    }

    @Test
    void rollbackEscalationWithPrimaryCauseDoesNotSelfSuppress() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        assertThat(lifecycle.fail("ACTIVATION_FAILED", "activate", "activate", null, primaryCause).getCause())
                .isSameAs(primaryCause);

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
        lifecycle.escalateRollbackIncomplete("close", cleanupFailure);

        // then
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        List<CapturedLogEvent> events = logs.events().stream()
                .filter(event -> BootstrapLifecycleState.class.getName().equals(event.logger()))
                .toList();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.message()).isEqualTo("Bootstrap operation rejected");
            assertThat(event.fields())
                    .containsEntry("failureCode", FAILED_ROLLBACK_INCOMPLETE)
                    .containsEntry("phase", "close")
                    .containsEntry("subphase", "close");
            assertThat(event.throwableDigest()).contains(IllegalStateException.class.getName());
        });
    }

    @Test
    void firstCloseFromFailedOwnsResourcesAndReplaysItsOutcome() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapResources resources = mock(BootstrapResources.class);
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        IllegalStateException cleanupFailure = new IllegalStateException("Cleanup failure");
        lifecycle.attachResources(resources);
        assertThat(lifecycle.fail("ACTIVATION_FAILED", "activate", "activate", null, primaryCause).getCause())
                .isSameAs(primaryCause);

        // when
        BootstrapLifecycleState.CloseAcquisition first = lifecycle.acquireClose();
        lifecycle.markClosed();
        lifecycle.completeClose(first.flight(), cleanupFailure);
        BootstrapLifecycleState.CloseAcquisition repeated = lifecycle.acquireClose();

        // then
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
        BootstrapException initial = lifecycle.fail(
                "PREPARATION_FAILED",
                "prepare",
                "prepare",
                null,
                primaryCause
        );

        try (AutoconfigureSession session = mock(AutoconfigureSession.class)) {
            // when
            Throwable thrown = catchThrowable(() -> lifecycle.completePrepare(session));

            // then
            assertThat(thrown).isInstanceOf(BootstrapException.class);
            BootstrapException rejection = (BootstrapException) thrown;
            assertThat(rejection.failure().primaryCode()).isEqualTo("PREPARATION_FAILED");
            assertThat(rejection.failure().effectiveCode()).isEqualTo("PREPARATION_FAILED");
            assertThat(rejection.getCause()).isSameAs(initial.getCause()).isSameAs(primaryCause);
            assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
            assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        }
    }

    @Test
    void completePrepareAfterCloseRejectsWithoutBindingSession() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
        lifecycle.markClosed();
        lifecycle.completeClose(acquisition.flight(), null);

        try (AutoconfigureSession session = mock(AutoconfigureSession.class)) {
            // when
            Throwable thrown = catchThrowable(() -> lifecycle.completePrepare(session));

            // then
            assertThat(thrown).isInstanceOf(BootstrapException.class);
            BootstrapException rejection = (BootstrapException) thrown;
            assertThat(rejection.failure().primaryCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
            assertThat(rejection.failure().effectiveCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
            assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
            assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        }
    }

    @Test
    void reentrantPrepareGuardDoesNotTerminalizeLifecycle() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        lifecycle.beginPrepare();

        // when
        Throwable thrown = catchThrowable(lifecycle::beginPrepare);

        // then
        assertThat(thrown).isInstanceOf(BootstrapException.class);
        BootstrapException rejection = (BootstrapException) thrown;
        assertThat(rejection.failure().primaryCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
        assertThat(rejection.failure().effectiveCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.PREPARING);
    }

    @Test
    void activationWithoutPreparationGuardDoesNotTerminalizeLifecycle() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();

        // when
        Throwable thrown = catchThrowable(lifecycle::beginActivation);

        // then
        assertThat(thrown).isInstanceOf(BootstrapException.class);
        BootstrapException rejection = (BootstrapException) thrown;
        assertThat(rejection.failure().primaryCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
        assertThat(rejection.failure().effectiveCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.NEW);
    }

    @Test
    void failed_session_is_detached_once_and_keeps_its_evidence() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable("prepared-version");
        when(session.evidence()).thenReturn(evidence);
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
        IllegalStateException customizationFailure = new IllegalStateException("Customization failed");
        assertThat(lifecycle.fail(
                "CUSTOMIZATION_FAILED",
                "customization",
                "customization",
                null,
                customizationFailure
        ).getCause()).isSameAs(customizationFailure);

        // when
        AutoconfigureSession detached = lifecycle.detachFailedSession();
        AutoconfigureSession repeated = lifecycle.detachFailedSession();
        BootstrapLifecycleState.CloseAcquisition close = lifecycle.acquireClose();
        lifecycle.completeClose(close.flight(), null);

        // then
        assertThat(detached).isSameAs(session);
        assertThat(repeated).isNull();
        assertThat(close.context().session()).isNull();
        assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
        assertThat(lifecycle.currentEvidence()).isSameAs(evidence);
    }

    @Test
    void prepared_session_is_not_detached_before_failure() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        when(session.evidence()).thenReturn(ExtensionRuntimeEvidence.unavailable("prepared-version"));
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);

        // when
        AutoconfigureSession detached = lifecycle.detachFailedSession();

        // then
        assertThat(detached).isNull();
        assertThat(lifecycle.requireSession()).isSameAs(session);
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.PREPARED);
    }

    @Test
    void close_moves_session_and_failure_cleanup_cannot_take_it_back() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable("prepared-version");
        when(session.evidence()).thenReturn(evidence);
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
        IllegalStateException customizationFailure = new IllegalStateException("Customization failed");
        assertThat(lifecycle.fail(
                "CUSTOMIZATION_FAILED",
                "customization",
                "customization",
                null,
                customizationFailure
        ).getCause()).isSameAs(customizationFailure);

        // when
        BootstrapLifecycleState.CloseAcquisition close = lifecycle.acquireClose();
        AutoconfigureSession detached = lifecycle.detachFailedSession();
        lifecycle.completeClose(close.flight(), null);

        // then
        assertThat(close.context().session()).isSameAs(session);
        assertThat(detached).isNull();
        assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
        assertThat(lifecycle.currentEvidence()).isSameAs(evidence);
    }

    @Test
    void activation_moves_session_to_resources_and_active_commit_does_not_restore_it() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        ExtensionRuntimeEvidence evidence = ExtensionRuntimeEvidence.unavailable("prepared-version");
        when(session.evidence()).thenReturn(evidence);
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
        assertThat(lifecycle.beginActivation()).isSameAs(session);
        BootstrapResources resources = new BootstrapResources();
        resources.push(session, "autoconfigure-session");

        // when
        lifecycle.attachResources(resources);
        lifecycle.commitActive();
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        assertThat(lifecycle.fail(
                "ACTIVATION_FAILED",
                "activate",
                "activate",
                null,
                activationFailure
        ).getCause()).isSameAs(activationFailure);
        AutoconfigureSession detached = lifecycle.detachFailedSession();
        BootstrapLifecycleState.CloseAcquisition close = lifecycle.acquireClose();
        resources.close();
        lifecycle.completeClose(close.flight(), null);

        // then
        assertThat(detached).isNull();
        assertThat(close.context().resources()).isSameAs(resources);
        assertThat(close.context().session()).isNull();
        assertThat(catchThrowable(lifecycle::requireSession)).isInstanceOf(BootstrapException.class);
        assertThat(lifecycle.currentEvidence()).isSameAs(evidence);
    }
}
