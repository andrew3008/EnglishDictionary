package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Адаптер {@link PlatformTracingScrubbingMetricsBinder#toEnumKeyedState}: сужение динамического wire key-space до {@link ScrubbingMetricKey}. */
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
        // "failures" намеренно отсутствует в wire-карте — проверяет sparse-семантику: 0, не absent.
        ScrubbingValues values = new ScrubbingValues(true, 3L, 12L, 0L, Map.of("drop", 5L, "hash", 2L));
        JmxOwnerState<ScrubbingValues> present = new JmxOwnerState.Present<>(values, 1L);

        JmxOwnerState<Map<ScrubbingMetricKey, Long>> mapped = PlatformTracingScrubbingMetricsBinder.toEnumKeyedState(present);

        Map<ScrubbingMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ScrubbingMetricKey, Long>>) mapped).values();
        assertThat(keyed.get(ScrubbingMetricKey.DROP)).isEqualTo(5L);
        assertThat(keyed.get(ScrubbingMetricKey.HASH)).isEqualTo(2L);
        assertThat(keyed.get(ScrubbingMetricKey.FAILURES)).isZero();
        assertThat(keyed).hasSize(ScrubbingMetricKey.values().length);
    }

    @Test
    void collapseCountersReachMicrometerAsCumulativeKeys() {
        ScrubbingValues values = new ScrubbingValues(true, 3L, 12L, 0L,
                Map.of("collapse_mode_activations", 1L, "collapsed_spans", 7L));
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
