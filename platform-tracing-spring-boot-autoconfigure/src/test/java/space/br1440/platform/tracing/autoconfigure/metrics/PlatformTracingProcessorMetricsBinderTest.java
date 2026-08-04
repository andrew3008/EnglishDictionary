package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorWatchdogValues;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Адаптеры {@link PlatformTracingProcessorMetricsBinder} для трёх owners: watchdog/metrics —
 * скаляр→карта, composite — объединение скаляра {@code errorsTotal} и sparse-карты
 * {@code errorsByName} в один {@link ProcessorCompositeErrorKey}.
 */
class PlatformTracingProcessorMetricsBinderTest {

    @Test
    void absentWatchdogOwnerPreservesGenerationWithoutMapping() {
        JmxOwnerState<ProcessorWatchdogValues> absent = new JmxOwnerState.Absent<>(1L);

        JmxOwnerState<Map<ProcessorWatchdogMetricKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toWatchdogKeyedState(absent);

        assertThat(mapped).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(mapped.generation()).isEqualTo(1L);
    }

    @Test
    void presentWatchdogOwnerMapsCumulativeScalarsOnly() {
        ProcessorWatchdogValues values = new ProcessorWatchdogValues(11L, 4L, 3, 1);
        JmxOwnerState<ProcessorWatchdogValues> present = new JmxOwnerState.Present<>(values, 2L);

        JmxOwnerState<Map<ProcessorWatchdogMetricKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toWatchdogKeyedState(present);

        Map<ProcessorWatchdogMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ProcessorWatchdogMetricKey, Long>>) mapped).values();
        assertThat(keyed)
                .containsEntry(ProcessorWatchdogMetricKey.FORCED_SPAN_CLOSES, 11L)
                .containsEntry(ProcessorWatchdogMetricKey.FORCED_TRACE_CLOSES, 4L)
                .hasSize(2);
    }

    @Test
    void absentMetricsOwnerPreservesGenerationWithoutMapping() {
        JmxOwnerState<ProcessorMetricsValues> absent = new JmxOwnerState.Absent<>(6L);

        JmxOwnerState<Map<ProcessorMetricsMetricKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toMetricsKeyedState(absent);

        assertThat(mapped).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(mapped.generation()).isEqualTo(6L);
    }

    @Test
    void presentMetricsOwnerMapsAllThreeScalars() {
        ProcessorMetricsValues values = new ProcessorMetricsValues(5L, 6L, 7L);
        JmxOwnerState<ProcessorMetricsValues> present = new JmxOwnerState.Present<>(values, 3L);

        JmxOwnerState<Map<ProcessorMetricsMetricKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toMetricsKeyedState(present);

        Map<ProcessorMetricsMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ProcessorMetricsMetricKey, Long>>) mapped).values();
        assertThat(keyed)
                .containsEntry(ProcessorMetricsMetricKey.DROPPED_ATTRIBUTES, 5L)
                .containsEntry(ProcessorMetricsMetricKey.DROPPED_EVENTS, 6L)
                .containsEntry(ProcessorMetricsMetricKey.DROPPED_LINKS, 7L);
    }

    @Test
    void absentCompositeOwnerPreservesGenerationWithoutMapping() {
        JmxOwnerState<ProcessorCompositeValues> absent = new JmxOwnerState.Absent<>(8L);

        JmxOwnerState<Map<ProcessorCompositeErrorKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toCompositeKeyedState(absent);

        assertThat(mapped).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(mapped.generation()).isEqualTo(8L);
    }

    @Test
    void presentCompositeOwnerCombinesTotalScalarWithSparseErrorsByName() {
        // errorsByName содержит только два из шести allowlist-имён — остальные должны стать 0, не absent.
        Map<String, Long> errorsByName = Map.of(
                "SpanWatchdogProcessor", 3L,
                "ScrubbingSpanProcessor", 1L);
        ProcessorCompositeValues values = new ProcessorCompositeValues(4L, errorsByName);
        JmxOwnerState<ProcessorCompositeValues> present = new JmxOwnerState.Present<>(values, 1L);

        JmxOwnerState<Map<ProcessorCompositeErrorKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toCompositeKeyedState(present);

        Map<ProcessorCompositeErrorKey, Long> keyed = ((JmxOwnerState.Present<Map<ProcessorCompositeErrorKey, Long>>) mapped).values();
        assertThat(keyed.get(ProcessorCompositeErrorKey.TOTAL)).isEqualTo(4L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.SPAN_WATCHDOG_PROCESSOR)).isEqualTo(3L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.SCRUBBING_SPAN_PROCESSOR)).isEqualTo(1L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.METRICS_SPAN_PROCESSOR)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.VALIDATING_SPAN_PROCESSOR)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.PLATFORM_COMPOSITE_SPAN_PROCESSOR)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.PLATFORM_DROP_OLDEST_EXPORT_SPAN_PROCESSOR)).isZero();
        assertThat(keyed).hasSize(ProcessorCompositeErrorKey.values().length);
    }
}
