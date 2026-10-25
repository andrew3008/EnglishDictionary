package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class PlatformTracingScrubbingMetricsBinderTest {

    @Test
    void absentOwnerPreservesGenerationWithoutMapping() {
        JmxOwnerState<ScrubbingValues> absent = new JmxOwnerState.Absent<>(9L);

        JmxOwnerState<Map<ScrubbingMetricKey, Long>> mapped = PlatformTracingScrubbingMetricsBinder.toEnumKeyedState(absent);

        assertThat(mapped).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(mapped.generation()).isEqualTo(9L);
    }

    @Test
    void presentOwnerMapsKnownWireKeysAndDefaultsMissingKeysToZero() {
        ScrubbingValues values = new ScrubbingValues(true, 3L, 12L,
                Map.of(
                        JmxWireContract.KEY_SCRUBBING_METRICS_DROP, 5L,
                        JmxWireContract.KEY_SCRUBBING_METRICS_HASH, 2L
                )
        );
        JmxOwnerState<ScrubbingValues> present = new JmxOwnerState.Present<>(values, 1L);

        JmxOwnerState<Map<ScrubbingMetricKey, Long>> mapped = PlatformTracingScrubbingMetricsBinder.toEnumKeyedState(present);

        Map<ScrubbingMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ScrubbingMetricKey, Long>>) mapped).values();
        assertThat(keyed.get(ScrubbingMetricKey.DROP)).isEqualTo(5L);
        assertThat(keyed.get(ScrubbingMetricKey.HASH)).isEqualTo(2L);
        assertThat(keyed.get(ScrubbingMetricKey.FAILURES)).isZero();
        assertThat(keyed).hasSize(ScrubbingMetricKey.values().length);
    }

    @Test
    void skippedCircuitOpenCounterReachesMicrometerKeySpace() {
        // given
        ScrubbingValues values = new ScrubbingValues(true, 3L, 12L, Map.of(
                JmxWireContract.KEY_SCRUBBING_METRICS_SKIPPED_CIRCUIT_OPEN, 4L));
        JmxOwnerState<ScrubbingValues> present = new JmxOwnerState.Present<>(values, 1L);

        // when
        Map<ScrubbingMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ScrubbingMetricKey, Long>>)
                PlatformTracingScrubbingMetricsBinder.toEnumKeyedState(present)).values();

        // then
        assertThat(keyed.get(ScrubbingMetricKey.SKIPPED_CIRCUIT_OPEN)).isEqualTo(4L);
    }

    @Test
    void collapseCountersReachMicrometerAsCumulativeKeys() {
        ScrubbingValues values = new ScrubbingValues(true, 3L, 12L,
                Map.of(
                        JmxWireContract.KEY_SCRUBBING_METRICS_COLLAPSE_MODE_ACTIVATIONS, 1L,
                        JmxWireContract.KEY_SCRUBBING_METRICS_COLLAPSED_SPANS, 7L));
        JmxOwnerState<ScrubbingValues> present = new JmxOwnerState.Present<>(values, 1L);

        Map<ScrubbingMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ScrubbingMetricKey, Long>>)
                PlatformTracingScrubbingMetricsBinder.toEnumKeyedState(present)).values();

        assertThat(keyed.get(ScrubbingMetricKey.COLLAPSE_MODE_ACTIVATIONS)).isEqualTo(1L);
        assertThat(keyed.get(ScrubbingMetricKey.COLLAPSED_SPANS)).isEqualTo(7L);
    }

    @Test
    void runtimeStateGaugeReportsActiveAndDegradedAsMutuallyExclusiveStates() {
        assertThat(PlatformTracingScrubbingMetricsBinder.runtimeStateValue(ScrubbingRuntimeState.ACTIVE, 1.0d))
                .isEqualTo(1.0d);
        assertThat(PlatformTracingScrubbingMetricsBinder
                .runtimeStateValue(ScrubbingRuntimeState.DEGRADED_FAIL_CLOSED, 1.0d))
                .isEqualTo(0.0d);

        assertThat(PlatformTracingScrubbingMetricsBinder.runtimeStateValue(ScrubbingRuntimeState.ACTIVE, 0.0d))
                .isEqualTo(0.0d);
        assertThat(PlatformTracingScrubbingMetricsBinder
                .runtimeStateValue(ScrubbingRuntimeState.DEGRADED_FAIL_CLOSED, 0.0d))
                .isEqualTo(1.0d);
    }

    @Test
    void unknownOwnerReportsNaNInsteadOfClaimingAHealthyRuntime() {
        assertThat(PlatformTracingScrubbingMetricsBinder.runtimeStateValue(ScrubbingRuntimeState.ACTIVE, Double.NaN))
                .isNaN();
        assertThat(PlatformTracingScrubbingMetricsBinder
                .runtimeStateValue(ScrubbingRuntimeState.DEGRADED_FAIL_CLOSED, Double.NaN))
                .isNaN();
    }
}
