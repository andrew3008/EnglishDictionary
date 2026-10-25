package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.SafeExporterValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlatformTracingExportMetricsBinderTest {

    @Test
    void lifecycleGaugeReturnsNaNWhenObservationReadFails() {
        // given
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(client.export().observation()).thenThrow(mock(AgentReadinessCoherenceException.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingExportMetricsBinder(client, () -> 0L).bindTo(registry);

        // when
        double value = registry.get("platform.tracing.export.processor.lifecycle")
                .tag("state", "active")
                .gauge()
                .value();

        // then
        assertThat(value).isNaN();
    }

    @Test
    void absentProcessorOwnerPreservesGenerationWithoutMapping() {
        // given
        JmxOwnerState<ExportProcessorValues> absent = new JmxOwnerState.Absent<>(5L);

        // when
        JmxOwnerState<Map<ExportProcessorMetricKey, Long>> mapped =
                PlatformTracingExportMetricsBinder.toProcessorKeyedState(absent);

        // then
        assertThat(mapped).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(mapped.generation()).isEqualTo(5L);
    }

    @Test
    void presentProcessorOwnerMapsAllScalarsToEnumKeys() {
        // given
        ExportProcessorValues values = new ExportProcessorValues(
                100, 10, 7L, 3L, 2L, 1L, ExportProcessorValues.LifecycleState.ACTIVE, 5L, 6L, 8L
        );
        JmxOwnerState<ExportProcessorValues> present = new JmxOwnerState.Present<>(values, 4L);

        // when
        JmxOwnerState<Map<ExportProcessorMetricKey, Long>> mapped =
                PlatformTracingExportMetricsBinder.toProcessorKeyedState(present);

        // then
        assertThat(mapped).isInstanceOf(JmxOwnerState.Present.class);
        assertThat(mapped.generation()).isEqualTo(4L);
        Map<ExportProcessorMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ExportProcessorMetricKey, Long>>) mapped).values();
        assertThat(keyed)
                .containsEntry(ExportProcessorMetricKey.DROPPED_OVERFLOW, 7L)
                .containsEntry(ExportProcessorMetricKey.DROPPED_AFTER_SHUTDOWN, 3L)
                .containsEntry(ExportProcessorMetricKey.FAILURES, 2L)
                .containsEntry(ExportProcessorMetricKey.TIMEOUTS, 1L)
                .containsEntry(ExportProcessorMetricKey.SHUTDOWN_FAILURES, 5L)
                .containsEntry(ExportProcessorMetricKey.DROPPED_AFTER_FAILURE, 6L)
                .containsEntry(ExportProcessorMetricKey.DISCARDED_QUEUED, 8L);
    }

    @Test
    void absentExporterOwnerPreservesGenerationWithoutMapping() {
        // given
        JmxOwnerState<SafeExporterValues> absent = new JmxOwnerState.Absent<>(2L);

        // when
        JmxOwnerState<Map<ExportExporterMetricKey, Long>> mapped =
                PlatformTracingExportMetricsBinder.toExporterKeyedState(absent);

        // then
        assertThat(mapped).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(mapped.generation()).isEqualTo(2L);
    }

    @Test
    void presentExporterOwnerMapsKnownWireKeysAndDefaultsMissingKeysToZero() {
        // given
        SafeExporterValues values = new SafeExporterValues(
                Map.of(
                        JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES, 9L,
                        JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 100L
                )
        );
        JmxOwnerState<SafeExporterValues> present = new JmxOwnerState.Present<>(values, 1L);

        // when
        JmxOwnerState<Map<ExportExporterMetricKey, Long>> mapped =
                PlatformTracingExportMetricsBinder.toExporterKeyedState(present);

        // then
        Map<ExportExporterMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ExportExporterMetricKey, Long>>) mapped).values();
        assertThat(keyed.get(ExportExporterMetricKey.EXPORT_BATCHES)).isEqualTo(9L);
        assertThat(keyed.get(ExportExporterMetricKey.EXPORTED_SPANS)).isEqualTo(100L);
        assertThat(keyed.get(ExportExporterMetricKey.SHUTDOWN_FAILURES)).isZero();
        assertThat(keyed).hasSize(ExportExporterMetricKey.values().length);
    }

    @Test
    void presentExporterOwnerMapsRejectionCountersFromTheWireMap() {
        // given
        SafeExporterValues values = new SafeExporterValues(
                Map.of(
                        JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS, 7L,
                        JmxWireContract.KEY_SAFE_EXPORTER_METRICS_PARTIAL_SUCCESS_BATCHES, 2L,
                        JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS, 5L,
                        JmxWireContract.KEY_SAFE_EXPORTER_METRICS_ACCEPTED_UNKNOWN_BATCHES, 1L
                )
        );
        JmxOwnerState<SafeExporterValues> present = new JmxOwnerState.Present<>(values, 3L);

        // when
        JmxOwnerState<Map<ExportExporterMetricKey, Long>> mapped = PlatformTracingExportMetricsBinder.toExporterKeyedState(present);

        // then
        Map<ExportExporterMetricKey, Long> keyed = ((JmxOwnerState.Present<Map<ExportExporterMetricKey, Long>>) mapped).values();
        assertThat(keyed)
                .containsEntry(ExportExporterMetricKey.EXPORTED_SPANS, 7L)
                .containsEntry(ExportExporterMetricKey.PARTIAL_SUCCESS_BATCHES, 2L)
                .containsEntry(ExportExporterMetricKey.REJECTED_SPANS, 5L)
                .containsEntry(ExportExporterMetricKey.ACCEPTED_UNKNOWN_BATCHES, 1L);
    }
}
