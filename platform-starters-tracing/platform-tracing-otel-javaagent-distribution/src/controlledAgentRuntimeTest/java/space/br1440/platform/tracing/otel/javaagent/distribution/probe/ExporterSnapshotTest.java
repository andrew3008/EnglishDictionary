package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularDataSupport;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class ExporterSnapshotTest {

    private static final String AGENT_INSTANCE_ID = "790530db-65b9-4fd3-9df3-89fc0ab3c711";
    private static final String REPLACEMENT_AGENT_INSTANCE_ID = "75e147f8-525e-4867-b453-f90835ab9736";
    private static final String PARTIAL_SUCCESS_BATCHES = JmxWireContract.KEY_SAFE_EXPORTER_METRICS_PARTIAL_SUCCESS_BATCHES;
    private static final String REJECTED_SPANS = JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS;
    private static final String EXPORTED_SPANS = JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS;

    @Test
    void computes_deltas_from_nonzero_baseline() {
        // given
        ExporterSnapshot baseline = snapshot(AGENT_INSTANCE_ID, 2L, 7L, 11L, 13L);
        ExporterSnapshot current = snapshot(AGENT_INSTANCE_ID, 2L, 8L, 12L, 14L);

        // when
        ExporterSnapshot.MetricsDelta delta = current.deltaFrom(baseline);

        // then
        assertThat(delta).isEqualTo(new ExporterSnapshot.MetricsDelta(1L, 1L, 1L));
        assertThat(delta.satisfiesPartialSuccess()).isTrue();
    }

    @Test
    void does_not_accept_warning_only_partial_success() {
        // given
        ExporterSnapshot.MetricsDelta delta = new ExporterSnapshot.MetricsDelta(1L, 0L, 2L);

        // when
        boolean observed = delta.satisfiesPartialSuccess();

        // then
        assertThat(observed).isFalse();
    }

    @Test
    void does_not_accept_rejection_without_exported_span() {
        // given
        ExporterSnapshot.MetricsDelta delta = new ExporterSnapshot.MetricsDelta(1L, 1L, 0L);

        // when
        boolean observed = delta.satisfiesPartialSuccess();

        // then
        assertThat(observed).isFalse();
    }

    @Test
    void rejects_counter_reset() {
        // given
        ExporterSnapshot baseline = snapshot(AGENT_INSTANCE_ID, 2L, 7L, 11L, 13L);
        ExporterSnapshot current = snapshot(AGENT_INSTANCE_ID, 2L, 6L, 12L, 14L);

        // when
        Throwable thrown = catchThrowable(() -> current.deltaFrom(baseline));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Exporter counter decreased: partialSuccessBatches baseline=7, current=6");
    }

    @Test
    void rejects_agent_replacement() {
        // given
        ExporterSnapshot baseline = snapshot(AGENT_INSTANCE_ID, 2L, 7L, 11L, 13L);
        ExporterSnapshot current = snapshot(REPLACEMENT_AGENT_INSTANCE_ID, 2L, 8L, 12L, 14L);

        // when
        Throwable thrown = catchThrowable(() -> current.deltaFrom(baseline));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Agent instance changed while observing OTLP partial success: baseline=%s, current=%s",
                        AGENT_INSTANCE_ID,
                        REPLACEMENT_AGENT_INSTANCE_ID
                );
    }

    @Test
    void rejects_exporter_replacement() {
        // given
        ExporterSnapshot baseline = snapshot(AGENT_INSTANCE_ID, 2L, 7L, 11L, 13L);
        ExporterSnapshot current = snapshot(AGENT_INSTANCE_ID, 3L, 8L, 12L, 14L);

        // when
        Throwable thrown = catchThrowable(() -> current.deltaFrom(baseline));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Safe exporter owner changed while observing OTLP partial success: baseline=2, current=3");
    }

    @Test
    void rejects_negative_counter_on_construction() {
        // when
        Throwable thrown = catchThrowable(() -> snapshot(AGENT_INSTANCE_ID, 1L, 0L, 0L, -1L));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("exportedSpans must not be negative: -1");
    }

    @Test
    void decodes_required_export_metrics() throws OpenDataException {
        // given
        CompositeData observation = exportObservation(
                AGENT_INSTANCE_ID,
                true,
                3L,
                List.of(
                        metric(PARTIAL_SUCCESS_BATCHES, 11L),
                        metric(REJECTED_SPANS, 13L),
                        metric(EXPORTED_SPANS, 17L),
                        metric("export_batches", 19L)
                )
        );

        // when
        ExporterSnapshot snapshot = ExporterSnapshot.decode(observation);

        // then
        assertThat(snapshot).isEqualTo(snapshot(AGENT_INSTANCE_ID, 3L, 11L, 13L, 17L));
    }

    @Test
    void rejects_foreign_open_type() throws OpenDataException {
        // given
        CompositeData exporter = (CompositeData) exportObservation(AGENT_INSTANCE_ID, true, 1L, requiredMetrics())
                .get(JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER);

        // when
        Throwable thrown = catchThrowable(() -> ExporterSnapshot.decode(exporter));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("ExportObservation OpenType differs from the generated contract");
    }

    @Test
    void rejects_missing_required_metric() throws OpenDataException {
        // given
        CompositeData observation = exportObservation(
                AGENT_INSTANCE_ID,
                true,
                1L,
                List.of(metric(PARTIAL_SUCCESS_BATCHES, 1L), metric(REJECTED_SPANS, 1L))
        );

        // when
        Throwable thrown = catchThrowable(() -> ExporterSnapshot.decode(observation));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required safe exporter metric is absent: exported_spans");
    }

    @Test
    void rejects_negative_required_metric() throws OpenDataException {
        // given
        CompositeData observation = exportObservation(
                AGENT_INSTANCE_ID,
                true,
                1L,
                List.of(metric(PARTIAL_SUCCESS_BATCHES, 1L), metric(REJECTED_SPANS, -1L), metric(EXPORTED_SPANS, 1L))
        );

        // when
        Throwable thrown = catchThrowable(() -> ExporterSnapshot.decode(observation));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rejectedSpans must not be negative: -1");
    }

    @Test
    void rejects_absent_exporter_owner() throws OpenDataException {
        // given
        CompositeData observation = exportObservation(AGENT_INSTANCE_ID, false, 0L, requiredMetrics());

        // when
        Throwable thrown = catchThrowable(() -> ExporterSnapshot.decode(observation));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Safe exporter owner is absent from ExportObservation");
    }

    @Test
    void rejects_noncanonical_agent_instance_id() throws OpenDataException {
        // given
        String noncanonicalId = "790530DB-65B9-4FD3-9DF3-89FC0AB3C711";
        CompositeData observation = exportObservation(noncanonicalId, true, 1L, requiredMetrics());

        // when
        Throwable thrown = catchThrowable(() -> ExporterSnapshot.decode(observation));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("agentInstanceId must be a canonical lowercase non-nil UUID: " + noncanonicalId);
    }

    @Test
    void rejects_nonpositive_present_owner_generation() throws OpenDataException {
        // given
        CompositeData observation = exportObservation(AGENT_INSTANCE_ID, true, 0L, requiredMetrics());

        // when
        Throwable thrown = catchThrowable(() -> ExporterSnapshot.decode(observation));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ownerGeneration must be positive: 0");
    }

    private static List<CompositeData> requiredMetrics() throws OpenDataException {
        return List.of(metric(PARTIAL_SUCCESS_BATCHES, 1L), metric(REJECTED_SPANS, 1L), metric(EXPORTED_SPANS, 1L));
    }

    private static CompositeData metric(String key, long value) throws OpenDataException {
        return new CompositeDataSupport(
                JmxOpenTypes.SAFE_EXPORTER_METRICS.getRowType(),
                Map.of(
                        JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_KEY, key,
                        JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_VALUE, value
                )
        );
    }

    private static CompositeData exportObservation(String agentInstanceId,
                                                   boolean ownerPresent,
                                                   long ownerGeneration,
                                                   List<CompositeData> metrics) throws OpenDataException {
        CompositeData exporter = new CompositeDataSupport(
                JmxOpenTypes.SAFE_EXPORTER_VALUES,
                Map.of(
                        JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_PRESENT, ownerPresent,
                        JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_GENERATION, ownerGeneration,
                        JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_SAFE_EXPORTER_METRICS,
                        safeExporterMetrics(metrics)
                )
        );

        return new CompositeDataSupport(
                JmxOpenTypes.EXPORT_OBSERVATION,
                Map.of(
                        JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID, agentInstanceId,
                        JmxWireContract.FIELD_EXPORT_OBSERVATION_PROCESSOR, exportProcessorValues(),
                        JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER, exporter
                )
        );
    }

    private static TabularDataSupport safeExporterMetrics(List<CompositeData> metrics) {
        TabularDataSupport table = new TabularDataSupport(JmxOpenTypes.SAFE_EXPORTER_METRICS);
        metrics.forEach(table::put);
        return table;
    }

    private static CompositeData exportProcessorValues() throws OpenDataException {
        return new CompositeDataSupport(
                JmxOpenTypes.EXPORT_PROCESSOR_VALUES,
                Map.ofEntries(
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_PRESENT, false),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_GENERATION, 0L),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_CAPACITY, 0),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_SIZE, 0),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_OVERFLOW_TOTAL, 0L),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_AFTER_SHUTDOWN_TOTAL, 0L),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_FAILURES_TOTAL, 0L),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_TIMEOUTS_TOTAL, 0L),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_LIFECYCLE_STATE, ""),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_SHUTDOWN_FAILURES_TOTAL, 0L),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DROPPED_AFTER_FAILURE_TOTAL, 0L),
                        Map.entry(JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_DISCARDED_QUEUED_TOTAL, 0L)
                )
        );
    }

    private static ExporterSnapshot snapshot(String agentInstanceId,
                                             long ownerGeneration,
                                             long partialSuccessBatches,
                                             long rejectedSpans,
                                             long exportedSpans) {
        return new ExporterSnapshot(
                agentInstanceId,
                ownerGeneration,
                partialSuccessBatches,
                rejectedSpans,
                exportedSpans
        );
    }
}
