package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

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
}
