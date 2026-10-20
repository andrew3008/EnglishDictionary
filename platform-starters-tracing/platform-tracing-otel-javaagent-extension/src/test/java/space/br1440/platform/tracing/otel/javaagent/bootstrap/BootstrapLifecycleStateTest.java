package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

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
        BootstrapException initial = lifecycle.fail("INITIAL_FAILURE", "prepare", firstCause);
        BootstrapException repeated = lifecycle.fail("SUBSEQUENT_FAILURE", "activate", rejectedCause);

        // then
        assertThat(repeated.failure()).isSameAs(initial.failure());
        assertThat(repeated.failure().code()).isEqualTo("INITIAL_FAILURE");
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
        UnsupportedOperationException repeatedCleanupFailure = new UnsupportedOperationException("Repeated cleanup failure");

        // when
        lifecycle.escalateRollbackIncomplete("close", cleanupFailure);
        BootstrapException escalated = lifecycle.stickyFailure("test");
        lifecycle.escalateRollbackIncomplete("shutdown", repeatedCleanupFailure);
        BootstrapException repeated = lifecycle.stickyFailure("test");

        // then
        assertThat(escalated.failure().code()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.failure().code()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.getCause()).isSameAs(escalated.getCause()).isSameAs(cleanupFailure);
        assertThat(repeated.getCause().getSuppressed()).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void ordinaryFailureAfterRollbackEscalationKeepsEffectiveCode() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        IllegalStateException primaryCause = new IllegalStateException("Activation failure");
        UnsupportedOperationException cleanupFailure = new UnsupportedOperationException("Cleanup failure");
        IllegalArgumentException subsequentCause = new IllegalArgumentException("Subsequent failure");
        BootstrapException initial = lifecycle.fail("ACTIVATION_FAILED", "activate", primaryCause);

        // when
        lifecycle.escalateRollbackIncomplete("rollback", cleanupFailure);
        BootstrapException repeated = lifecycle.fail("SUBSEQUENT_FAILURE", "close", subsequentCause);

        // then
        assertThat(repeated.failure().code()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(repeated.getCause()).isSameAs(initial.getCause()).isSameAs(primaryCause);
        assertThat(repeated.getCause().getSuppressed()).containsExactly(cleanupFailure);
        assertThat(subsequentCause.getSuppressed()).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.FAILED);
    }
}
