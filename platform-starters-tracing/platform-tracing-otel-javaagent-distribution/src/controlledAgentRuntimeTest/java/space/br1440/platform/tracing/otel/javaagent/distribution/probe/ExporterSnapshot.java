package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

record ExporterSnapshot(String agentInstanceId,
                        long ownerGeneration,
                        long partialSuccessBatches,
                        long rejectedSpans,
                        long exportedSpans) {

    private static final String PARTIAL_SUCCESS_BATCHES = JmxWireContract.KEY_SAFE_EXPORTER_METRICS_PARTIAL_SUCCESS_BATCHES;
    private static final String REJECTED_SPANS = JmxWireContract.KEY_SAFE_EXPORTER_METRICS_REJECTED_SPANS;
    private static final String EXPORTED_SPANS = JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS;
    private static final Set<String> REQUIRED_METRICS = Set.of(PARTIAL_SUCCESS_BATCHES, REJECTED_SPANS, EXPORTED_SPANS);

    ExporterSnapshot {
        requireCanonicalUuid(agentInstanceId);
        requirePositive("ownerGeneration", ownerGeneration);
        requireNonNegative("partialSuccessBatches", partialSuccessBatches);
        requireNonNegative("rejectedSpans", rejectedSpans);
        requireNonNegative("exportedSpans", exportedSpans);
    }

    static ExporterSnapshot read(MBeanServer server) {
        return decode(JmxValues.attribute(
                server,
                JmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                JmxOpenTypes.EXPORT_OBSERVATION));
    }

    static ExporterSnapshot decode(CompositeData observation) {
        JmxValues.requireOpenType(observation, JmxOpenTypes.EXPORT_OBSERVATION, "ExportObservation");

        CompositeData exporter = JmxValues.composite(observation, JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER);
        if (!JmxValues.bool(exporter, JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_PRESENT)) {
            throw new IllegalStateException("Safe exporter owner is absent from ExportObservation");
        }

        Map<String, Long> metrics = requiredMetrics(
                JmxValues.tabular(exporter, JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_SAFE_EXPORTER_METRICS)
        );

        return new ExporterSnapshot(
                JmxValues.nonBlankString(observation, JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID),
                JmxValues.longValue(exporter, JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_GENERATION),
                metrics.get(PARTIAL_SUCCESS_BATCHES),
                metrics.get(REJECTED_SPANS),
                metrics.get(EXPORTED_SPANS)
        );
    }

    MetricsDelta deltaFrom(ExporterSnapshot baseline) {
        requireSameOwner(baseline);
        return new MetricsDelta(
                counterDelta("partialSuccessBatches", partialSuccessBatches, baseline.partialSuccessBatches),
                counterDelta("rejectedSpans", rejectedSpans, baseline.rejectedSpans),
                counterDelta("exportedSpans", exportedSpans, baseline.exportedSpans)
        );
    }

    private static Map<String, Long> requiredMetrics(TabularData table) {
        Map<String, Long> metrics = new HashMap<>();
        for (Object rawRow : table.values()) {
            CompositeData row = JmxValues.asComposite(rawRow, "safeExporterMetrics row");
            String key = JmxValues.nonBlankString(row, JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_KEY);
            long value = JmxValues.longValue(row, JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_VALUE);
            if (REQUIRED_METRICS.contains(key)) {
                metrics.put(key, value);
            }
        }

        for (String key : REQUIRED_METRICS) {
            if (!metrics.containsKey(key)) {
                throw new IllegalStateException("Required safe exporter metric is absent: " + key);
            }
        }
        return metrics;
    }

    private void requireSameOwner(ExporterSnapshot baseline) {
        if (!agentInstanceId.equals(baseline.agentInstanceId)) {
            throw new IllegalStateException(
                    "Agent instance changed while observing OTLP partial success: baseline=%s, current=%s"
                            .formatted(baseline.agentInstanceId, agentInstanceId));
        }
        if (ownerGeneration != baseline.ownerGeneration) {
            throw new IllegalStateException(
                    "Safe exporter owner changed while observing OTLP partial success: baseline=%d, current=%d"
                            .formatted(baseline.ownerGeneration, ownerGeneration));
        }
    }

    private static long counterDelta(String name, long current, long baseline) {
        if (current < baseline) {
            throw new IllegalStateException("Exporter counter decreased: %s baseline=%d, current=%d"
                    .formatted(name, baseline, current));
        }
        return current - baseline;
    }

    private static void requireCanonicalUuid(String value) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("agentInstanceId must be a canonical lowercase UUID: " + value, exception);
        }

        boolean nil = parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L;
        if (nil || !parsed.toString().equals(value)) {
            throw new IllegalStateException("agentInstanceId must be a canonical lowercase non-nil UUID: " + value);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void requirePositive(String name, long value) {
        if (value <= 0L) {
            throw new IllegalStateException("%s must be positive: %d".formatted(name, value));
        }
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0L) {
            throw new IllegalStateException("%s must not be negative: %d".formatted(name, value));
        }
    }

    record MetricsDelta(long partialSuccessBatches, long rejectedSpans, long exportedSpans) {

        static final String PARTIAL_SUCCESS_CRITERION =
                "partialSuccessBatches > 0 && rejectedSpans > 0 && exportedSpans > 0";

        boolean satisfiesPartialSuccess() {
            return partialSuccessBatches > 0L && rejectedSpans > 0L && exportedSpans > 0L;
        }
    }
}
