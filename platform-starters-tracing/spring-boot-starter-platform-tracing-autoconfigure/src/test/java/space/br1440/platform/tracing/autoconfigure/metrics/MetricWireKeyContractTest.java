package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class MetricWireKeyContractTest {

    @Test
    void samplerDecisionKeysMatchTheSupportedWireKeySpace() {
        // when/then
        assertThat(Arrays.stream(SamplerDecisionKey.values()).map(SamplerDecisionKey::wireKey).toList())
                .containsExactlyInAnyOrder(
                        "RECORD_AND_SAMPLE:force_header",
                        "RECORD_AND_SAMPLE:qa_trace",
                        "RECORD_AND_SAMPLE:parent_sampled",
                        "RECORD_AND_SAMPLE:route_ratio",
                        "RECORD_AND_SAMPLE:global_ratio",
                        "DROP:kill_switch",
                        "DROP:drop_path",
                        "DROP:parent_drop",
                        "DROP:route_ratio_drop",
                        "DROP:global_ratio_drop",
                        "DROP:fallback_drop");
    }

    @Test
    void scrubbingKeysMatchTheExportedCumulativeMetrics() {
        // when/then
        assertThat(Arrays.stream(ScrubbingMetricKey.values()).map(ScrubbingMetricKey::wireKey).toList())
                .containsExactlyInAnyOrder(
                        "drop",
                        "hash",
                        "mask",
                        "truncate",
                        "failures",
                        "hash_missing_key",
                        "circuit_breakers.failures.total",
                        "collapse_mode_activations",
                        "collapsed_spans");
    }

    @Test
    void exporterKeysMatchTheExportedCumulativeMetrics() {
        // when/then
        assertThat(Arrays.stream(ExportExporterMetricKey.values()).map(ExportExporterMetricKey::wireKey).toList())
                .containsExactlyInAnyOrder(
                        "export_batches",
                        "export_batch_failures",
                        "exported_spans",
                        "transport_dropped_spans",
                        "partial_success_batches",
                        "rejected_spans",
                        "accepted_unknown_batches",
                        "flush_failures",
                        "shutdown_failures",
                        "suppressed_spans_export_disabled"
                );
    }

    @Test
    void processorNamesMatchTheBoundedMetricTagAllowlist() {
        // when/then
        assertThat(ProcessorCompositeErrorKey.TOTAL.processorName()).isNull();
        assertThat(Arrays.stream(ProcessorCompositeErrorKey.values())
                .filter(key -> key != ProcessorCompositeErrorKey.TOTAL)
                .map(ProcessorCompositeErrorKey::processorName)
                .toList())
                .containsExactlyInAnyOrder(
                        "baggage",
                        "enrichment",
                        "scrubbing",
                        "validation",
                        "classification",
                        "span_timeout",
                        "span_limits_metrics"
                );
    }
}
