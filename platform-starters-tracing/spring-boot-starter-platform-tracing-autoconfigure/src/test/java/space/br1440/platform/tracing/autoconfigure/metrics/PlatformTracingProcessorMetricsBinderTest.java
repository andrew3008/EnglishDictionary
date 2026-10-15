package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorSpanTimeoutValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@SuppressWarnings("DataFlowIssue")
class PlatformTracingProcessorMetricsBinderTest {

    private static final String AGENT_INSTANCE_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void absentSpanTimeoutOwnerPreservesGenerationWithoutMapping() {
        JmxOwnerState<ProcessorSpanTimeoutValues> absent = new JmxOwnerState.Absent<>(1L);

        JmxOwnerState<Map<ProcessorSpanTimeoutMetricKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toSpanTimeoutKeyedState(absent);

        assertThat(mapped).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(mapped.generation()).isEqualTo(1L);
    }

    @Test
    void presentSpanTimeoutOwnerMapsCumulativeScalarsOnly() {
        ProcessorSpanTimeoutValues values = new ProcessorSpanTimeoutValues(
                new ProcessorSpanTimeoutValues.ForceEnd(11L, 4L, 13L),
                new ProcessorSpanTimeoutValues.Tracking(3, 1, 7, 10_000, 9L, 60_000, 1_024, 0L),
                new ProcessorSpanTimeoutValues.Rejections(2L, 5L, 8L),
                new ProcessorSpanTimeoutValues.Scanner(6L, 1_700_000_000_000L, "RUNNING")
        );
        JmxOwnerState<ProcessorSpanTimeoutValues> present = new JmxOwnerState.Present<>(values, 2L);

        JmxOwnerState<Map<ProcessorSpanTimeoutMetricKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toSpanTimeoutKeyedState(present);

        Map<ProcessorSpanTimeoutMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ProcessorSpanTimeoutMetricKey, Long>>) mapped).values();
        assertThat(keyed)
                .containsEntry(ProcessorSpanTimeoutMetricKey.FORCED_SPAN_END_ATTEMPTS, 11L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.FORCED_TRACE_END_ATTEMPTS, 4L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.TRACKING_EVICTED, 9L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.SPANS_REJECTED_PER_TRACE, 2L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.SPANS_REJECTED_GLOBAL, 5L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.SPANS_REJECTED_TRACE_CAPACITY, 8L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.SPAN_LEDGER_INVARIANT_VIOLATION, 0L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.SCAN_FAILURES, 6L)
                .containsEntry(ProcessorSpanTimeoutMetricKey.FORCE_END_FAILURES, 13L)
                .hasSize(9);
    }

    @Test
    void presentSpanTimeoutOwnerExposesActiveTrackedAndCapacityGauges() {
        // given
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        ProcessorSpanTimeoutValues values = new ProcessorSpanTimeoutValues(
                new ProcessorSpanTimeoutValues.ForceEnd(1L, 2L, 0L),
                new ProcessorSpanTimeoutValues.Tracking(3, 4, 5, 10_000, 6L, 60_000, 1_024, 0L),
                new ProcessorSpanTimeoutValues.Rejections(0L, 0L, 0L),
                new ProcessorSpanTimeoutValues.Scanner(0L, 1_700_000_000_000L, "TERMINATED_ABNORMALLY"));
        when(client.processorMetrics().observation()).thenReturn(Optional.of(new ProcessorMetricsObservation(
                new ObservationCapture(AGENT_INSTANCE_ID, 1L, 1_000L, 1_001L),
                new JmxOwnerState.Present<>(values, 1L),
                new JmxOwnerState.Absent<>(0L),
                new JmxOwnerState.Absent<>(0L)
        )));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingProcessorMetricsBinder binder = new PlatformTracingProcessorMetricsBinder(client);

        // when
        binder.bindTo(registry);

        // then
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner").gauges())
                .extracting(gauge -> gauge.getId().getTag("state"))
                .containsExactlyInAnyOrderElementsOf(JmxWireContract.VALID_SCANNER_STATES.stream()
                        .map(state -> state.toLowerCase(Locale.ROOT))
                        .toList());
        assertThat(registry.find("platform.tracing.processor.span_timeout.active")
                .tag("scope", "spans").gauge().value()).isEqualTo(3.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.active")
                .tag("scope", "traces").gauge().value()).isEqualTo(4.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.tracked")
                .tag("scope", "traces").gauge().value()).isEqualTo(5.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.capacity")
                .tag("scope", "traces").gauge().value()).isEqualTo(10_000.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.capacity")
                .tag("scope", "spans").gauge().value()).isEqualTo(60_000.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.capacity")
                .tag("scope", "spans_per_trace").gauge().value()).isEqualTo(1_024.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner")
                .tag("state", "terminated_abnormally").gauge().value()).isEqualTo(1.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner")
                .tag("state", "running").gauge().value()).isEqualTo(0.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner")
                .tag("state", "disabled").gauge().value()).isEqualTo(0.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner")
                .tag("state", "stopped").gauge().value()).isEqualTo(0.0);
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner.last_success_epoch_millis")
                .gauge().value()).isEqualTo(1_700_000_000_000.0);
    }

    @Test
    void absentSpanTimeoutOwnerYieldsNanGauges() {
        // given
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(client.processorMetrics().observation()).thenReturn(Optional.of(new ProcessorMetricsObservation(
                new ObservationCapture(AGENT_INSTANCE_ID, 1L, 1_000L, 1_001L),
                new JmxOwnerState.Absent<>(1L),
                new JmxOwnerState.Absent<>(0L),
                new JmxOwnerState.Absent<>(0L)
        )));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingProcessorMetricsBinder(client).bindTo(registry);

        // when / then
        assertThat(registry.find("platform.tracing.processor.span_timeout.active")
                .tag("scope", "spans").gauge().value()).isNaN();
        assertThat(registry.find("platform.tracing.processor.span_timeout.tracked")
                .tag("scope", "traces").gauge().value()).isNaN();
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner")
                .tag("state", "running").gauge().value()).isNaN();
        assertThat(registry.find("platform.tracing.processor.span_timeout.scanner.last_success_epoch_millis")
                .gauge().value()).isNaN();
    }

    @Test
    void unreadableSpanTimeoutObservationYieldsNanGauges() {
        // given
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(client.processorMetrics().observation()).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingProcessorMetricsBinder(client).bindTo(registry);

        // when / then
        assertThat(registry.find("platform.tracing.processor.span_timeout.active")
                .tag("scope", "traces").gauge().value()).isNaN();
        assertThat(registry.find("platform.tracing.processor.span_timeout.capacity")
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
                "span_timeout", 3L,
                "scrubbing", 1L
        );
        ProcessorCompositeValues values = new ProcessorCompositeValues(4L, errorsByName);
        JmxOwnerState<ProcessorCompositeValues> present = new JmxOwnerState.Present<>(values, 1L);

        JmxOwnerState<Map<ProcessorCompositeErrorKey, Long>> mapped =
                PlatformTracingProcessorMetricsBinder.toCompositeKeyedState(present);

        Map<ProcessorCompositeErrorKey, Long> keyed = ((JmxOwnerState.Present<Map<ProcessorCompositeErrorKey, Long>>) mapped).values();
        assertThat(keyed.get(ProcessorCompositeErrorKey.TOTAL)).isEqualTo(4L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.SPAN_TIMEOUT)).isEqualTo(3L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.SCRUBBING)).isEqualTo(1L);
        assertThat(keyed.get(ProcessorCompositeErrorKey.BAGGAGE)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.ENRICHMENT)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.VALIDATION)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.CLASSIFICATION)).isZero();
        assertThat(keyed.get(ProcessorCompositeErrorKey.SPAN_LIMITS_METRICS)).isZero();
        assertThat(keyed).hasSize(ProcessorCompositeErrorKey.values().length);
    }
}
