package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorWatchdogValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@SuppressWarnings("DataFlowIssue")
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
        ProcessorWatchdogValues values = new ProcessorWatchdogValues(
                11L, 4L, 3, 1, 7, 10_000, 9L, 60_000, 1_024, 2L, 5L, 0L);
        JmxOwnerState<ProcessorWatchdogValues> present = new JmxOwnerState.Present<>(values, 2L);

        JmxOwnerState<Map<ProcessorWatchdogMetricKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toWatchdogKeyedState(present);

        Map<ProcessorWatchdogMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ProcessorWatchdogMetricKey, Long>>) mapped).values();
        assertThat(keyed)
                .containsEntry(ProcessorWatchdogMetricKey.FORCED_SPAN_CLOSES, 11L)
                .containsEntry(ProcessorWatchdogMetricKey.FORCED_TRACE_CLOSES, 4L)
                .containsEntry(ProcessorWatchdogMetricKey.TRACKING_EVICTED, 9L)
                .containsEntry(ProcessorWatchdogMetricKey.SPANS_REJECTED_PER_TRACE, 2L)
                .containsEntry(ProcessorWatchdogMetricKey.SPANS_REJECTED_GLOBAL, 5L)
                .containsEntry(ProcessorWatchdogMetricKey.SPAN_LEDGER_INVARIANT_VIOLATION, 0L)
                .hasSize(6);
    }

    @Test
    void presentWatchdogOwnerExposesActiveTrackedAndCapacityGauges() {
        // given
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        ProcessorWatchdogValues values = new ProcessorWatchdogValues(
                1L, 2L, 3, 4, 5, 10_000, 6L, 60_000, 1_024, 0L, 0L, 0L);
        when(client.processorMetrics().observation()).thenReturn(Optional.of(new ProcessorMetricsObservation(
                new ObservationCapture("agent-1", 1L, 1_000L, 1_001L),
                new JmxOwnerState.Present<>(values, 1L),
                new JmxOwnerState.Absent<>(0L),
                new JmxOwnerState.Absent<>(0L)
        )));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingProcessorMetricsBinder(client).bindTo(registry);

        // when / then
        assertThat(registry.find("platform.tracing.processor.watchdog.active")
                .tag("scope", "spans").gauge().value()).isEqualTo(3.0);
        assertThat(registry.find("platform.tracing.processor.watchdog.active")
                .tag("scope", "traces").gauge().value()).isEqualTo(4.0);
        assertThat(registry.find("platform.tracing.processor.watchdog.tracked")
                .tag("scope", "traces").gauge().value()).isEqualTo(5.0);
        assertThat(registry.find("platform.tracing.processor.watchdog.capacity")
                .tag("scope", "traces").gauge().value()).isEqualTo(10_000.0);
        assertThat(registry.find("platform.tracing.processor.watchdog.capacity")
                .tag("scope", "spans").gauge().value()).isEqualTo(60_000.0);
        assertThat(registry.find("platform.tracing.processor.watchdog.capacity")
                .tag("scope", "spans_per_trace").gauge().value()).isEqualTo(1_024.0);
    }

    @Test
    void absentWatchdogOwnerYieldsNanGauges() {
        // given
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(client.processorMetrics().observation()).thenReturn(Optional.of(new ProcessorMetricsObservation(
                new ObservationCapture("agent-1", 1L, 1_000L, 1_001L),
                new JmxOwnerState.Absent<>(1L),
                new JmxOwnerState.Absent<>(0L),
                new JmxOwnerState.Absent<>(0L)
        )));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingProcessorMetricsBinder(client).bindTo(registry);

        // when / then
        assertThat(registry.find("platform.tracing.processor.watchdog.active")
                .tag("scope", "spans").gauge().value()).isNaN();
        assertThat(registry.find("platform.tracing.processor.watchdog.tracked")
                .tag("scope", "traces").gauge().value()).isNaN();
    }

    @Test
    void unreadableWatchdogObservationYieldsNanGauges() {
        // given
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(client.processorMetrics().observation()).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingProcessorMetricsBinder(client).bindTo(registry);

        // when / then
        assertThat(registry.find("platform.tracing.processor.watchdog.active")
                .tag("scope", "traces").gauge().value()).isNaN();
        assertThat(registry.find("platform.tracing.processor.watchdog.capacity")
                .tag("scope", "traces").gauge().value()).isNaN();
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
        Map<String, Long> errorsByName = Map.of(
                "watchdog", 3L,
                "scrubbing", 1L
        );
        ProcessorCompositeValues values = new ProcessorCompositeValues(4L, errorsByName);
        JmxOwnerState<ProcessorCompositeValues> present = new JmxOwnerState.Present<>(values, 1L);

        JmxOwnerState<Map<ProcessorCompositeErrorKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toCompositeKeyedState(present);

        Map<ProcessorCompositeErrorKey, Long> keyed = ((JmxOwnerState.Present<Map<ProcessorCompositeErrorKey, Long>>) mapped).values();
        assertThat(keyed.get(ProcessorCompositeErrorKey.TOTAL)).isEqualTo(4L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.WATCHDOG)).isEqualTo(3L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.SCRUBBING)).isEqualTo(1L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.BAGGAGE)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.ENRICHMENT)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.VALIDATION)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.CLASSIFICATION)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.SPAN_LIMITS_METRICS)).isZero();
        assertThat(keyed).hasSize(ProcessorCompositeErrorKey.values().length);
    }
}
