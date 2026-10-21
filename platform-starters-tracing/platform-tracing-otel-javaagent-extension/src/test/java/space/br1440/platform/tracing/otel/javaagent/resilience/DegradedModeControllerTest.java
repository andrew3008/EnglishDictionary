package space.br1440.platform.tracing.otel.javaagent.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

class DegradedModeControllerTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    @Test
    void degrades_when_breaker_opens_and_publishes_flag() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        DegradedModeController controller = new DegradedModeController(
                diagnostics,
                new CircuitBreaker(2, COOLDOWN, new AtomicLong()::get)
        );
        assertThat(controller.isDegraded()).isFalse();
        assertThat(diagnostics.isDegradedMode()).isFalse();

        // when
        controller.recordFailure(controller.tryAcquire());
        controller.recordFailure(controller.tryAcquire());

        // then
        assertThat(controller.isDegraded()).isTrue();
        assertThat(diagnostics.isDegradedMode()).isTrue();
    }

    @Test
    void successful_probe_clears_published_flag() {
        // given
        AtomicLong nanoTime = new AtomicLong();
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        DegradedModeController controller = new DegradedModeController(
                diagnostics,
                new CircuitBreaker(1, COOLDOWN, nanoTime::get)
        );
        controller.recordFailure(controller.tryAcquire());
        nanoTime.addAndGet(COOLDOWN.toNanos());
        CircuitBreaker.Admission probe = controller.tryAcquire();
        assertThat(probe).isEqualTo(CircuitBreaker.Admission.PROBE);

        // when
        controller.recordSuccess(probe);

        // then
        assertThat(controller.isDegraded()).isFalse();
        assertThat(diagnostics.isDegradedMode()).isFalse();
    }
}
