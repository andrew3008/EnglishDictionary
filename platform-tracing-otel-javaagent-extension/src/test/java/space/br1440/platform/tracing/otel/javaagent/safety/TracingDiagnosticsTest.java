package space.br1440.platform.tracing.otel.javaagent.safety;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TracingDiagnostics}: счётчики отказов, агрегат suppressed_errors, snapshot для JMX.
 */
class TracingDiagnosticsTest {

    @Test
    void счётчики_и_snapshot_согласованы() {
        TracingDiagnostics d = new TracingDiagnostics();

        d.recordSamplerFailure();
        d.recordPropagatorFailure();
        d.recordResourceFailure();
        d.recordScopeFailure();
        d.setDegradedMode(true);

        assertThat(d.getSamplerFailures()).isEqualTo(1);
        assertThat(d.getPropagatorFailures()).isEqualTo(1);
        assertThat(d.getResourceFailures()).isEqualTo(1);
        assertThat(d.getScopeFailures()).isEqualTo(1);
        // suppressed_errors — надмножество: по одному инкременту на каждый специфичный отказ.
        assertThat(d.getSuppressedErrors()).isEqualTo(4);
        assertThat(d.getLastFailureEpochMs()).isGreaterThan(0);

        Map<String, Long> snapshot = d.snapshot();
        assertThat(snapshot).containsEntry("sampler.failures", 1L)
                .containsEntry("safe_wrapper.suppressed_errors", 4L)
                .containsEntry("degraded_mode.enabled", 1L);
    }
}
