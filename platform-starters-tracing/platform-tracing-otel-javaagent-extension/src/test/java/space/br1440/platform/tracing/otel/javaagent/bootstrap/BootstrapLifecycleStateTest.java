package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
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
        lifecycle.fail("ACTIVATION_FAILED", "activate", "activate", null, primaryCause);

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
        lifecycle.fail("ACTIVATION_FAILED", "activate", "activate", null, primaryCause);

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
        lifecycle.beginClose();
        lifecycle.markClosed();
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
    void closeFromFailedReleasesResourcesAndKeepsFailedTerminal() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapResources resources = mock(BootstrapResources.class);
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        lifecycle.attachResources(resources);
        lifecycle.fail("ACTIVATION_FAILED", "activate", "activate", null, primaryCause);

        // when
        BootstrapLifecycleState.CloseTransition first = lifecycle.beginClose();
        lifecycle.markClosed();
        BootstrapLifecycleState.CloseTransition repeated = lifecycle.beginClose();

        // then
        assertThat(first.started()).isTrue();
        assertThat(first.resources()).isSameAs(resources);
        assertThat(repeated.started()).isFalse();
        assertThat(repeated.resources()).isNull();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(lifecycle.failure().cause()).isSameAs(primaryCause);
    }

    @Test
    void completePrepareAfterFailureReturnsStickyFailureWithoutBindingSession() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        IllegalStateException primaryCause = new IllegalStateException("Preparation failure");
        BootstrapException initial = lifecycle.fail(
                "PREPARATION_FAILED",
                "prepare",
                "prepare",
                null,
                primaryCause
        );

        // when
        Throwable thrown = catchThrowable(() -> lifecycle.completePrepare(session));

        // then
        assertThat(thrown).isInstanceOf(BootstrapException.class);
        BootstrapException rejection = (BootstrapException) thrown;
        assertThat(rejection.failure().primaryCode()).isEqualTo("PREPARATION_FAILED");
        assertThat(rejection.failure().effectiveCode()).isEqualTo("PREPARATION_FAILED");
        assertThat(rejection.getCause()).isSameAs(initial.getCause()).isSameAs(primaryCause);
        assertThat(lifecycle.currentSession()).isNull();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void completePrepareAfterCloseRejectsWithoutBindingSession() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        lifecycle.beginClose();
        lifecycle.markClosed();

        // when
        Throwable thrown = catchThrowable(() -> lifecycle.completePrepare(session));

        // then
        assertThat(thrown).isInstanceOf(BootstrapException.class);
        BootstrapException rejection = (BootstrapException) thrown;
        assertThat(rejection.failure().primaryCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
        assertThat(rejection.failure().effectiveCode()).isEqualTo("REENTRANT_OR_OUT_OF_ORDER");
        assertThat(lifecycle.currentSession()).isNull();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
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
}
