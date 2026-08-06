package space.br1440.platform.tracing.autoconfigure.metrics.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CoalescingObservationMetricReaderTest {

    @Test
    void readMetric_missingKey_returnsZero() {
        assertThat(CoalescingObservationMetricReader.readMetric(Map.of(), "updates.applied")).isZero();
        assertThat(CoalescingObservationMetricReader.readMetric(Map.of("other", 1L), "updates.applied")).isZero();
    }

    @Test
    void readMetric_presentKey_returnsDoubleValue() {
        assertThat(CoalescingObservationMetricReader.readMetric(
                Map.of("updates.applied", 7L), "updates.applied")).isEqualTo(7.0);
    }

    @Test
    void readMetric_nullMetricsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> CoalescingObservationMetricReader.readMetric(null, "k"))
                .withMessageContaining("metrics");
    }

    @Test
    void readTriStateMetric_nullMetrics_returnsNaN() {
        assertThat(CoalescingObservationMetricReader.readTriStateMetric(null, "degraded_mode.enabled")).isNaN();
    }

    @Test
    void readTriStateMetric_presentMapMissingKey_returnsZeroNotNaN() {
        assertThat(CoalescingObservationMetricReader.readTriStateMetric(
                Map.of(), "degraded_mode.enabled")).isZero();
    }

    @Test
    void readTriStateMetric_presentKey_returnsDoubleValue() {
        assertThat(CoalescingObservationMetricReader.readTriStateMetric(
                Map.of("degraded_mode.enabled", 1L), "degraded_mode.enabled")).isEqualTo(1.0);
    }

    @Test
    void nullKeyRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> CoalescingObservationMetricReader.readMetric(Map.of(), null))
                .withMessageContaining("key");
        assertThatNullPointerException()
                .isThrownBy(() -> CoalescingObservationMetricReader.readTriStateMetric(Map.of(), null))
                .withMessageContaining("key");
    }
}
