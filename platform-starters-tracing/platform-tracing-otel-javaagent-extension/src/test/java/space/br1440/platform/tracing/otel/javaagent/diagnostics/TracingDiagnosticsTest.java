package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TracingDiagnostics}: счётчики отказов, агрегат suppressed_errors, snapshot для JMX.
 */
class TracingDiagnosticsTest {

    @Test
    void counters_and_snapshot_are_consistent() {
        // given
        TracingDiagnostics d = new TracingDiagnostics();

        // when
        d.recordSamplerFailure();
        d.recordPropagatorFailure();
        d.recordResourceFailure();
        d.setDegradedMode(true);

        // then
        assertThat(d.getSamplerFailures()).isEqualTo(1);
        assertThat(d.getPropagatorFailures()).isEqualTo(1);
        assertThat(d.getResourceFailures()).isEqualTo(1);
        assertThat(d.getSuppressedErrors()).isEqualTo(3);
        assertThat(d.getLastFailureEpochMs()).isGreaterThan(0);

        Map<String, Long> snapshot = d.snapshot();
        assertThat(snapshot).containsEntry("sampler.failures", 1L)
                .containsEntry("safe_wrapper.suppressed_errors", 3L)
                .containsEntry("degraded_mode.enabled", 1L)
                .doesNotContainKey("scope.failures");
    }
}
