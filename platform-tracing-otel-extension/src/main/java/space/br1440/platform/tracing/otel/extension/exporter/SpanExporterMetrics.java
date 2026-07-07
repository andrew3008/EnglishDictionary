package space.br1440.platform.tracing.otel.extension.exporter;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class SpanExporterMetrics {

    public static final String EXPORT_BATCHES = "export_batches";
    public static final String EXPORT_BATCH_FAILURES = "export_batch_failures";
    public static final String EXPORTED_SPANS = "exported_spans";
    public static final String TRANSPORT_DROPPED_SPANS = "transport_dropped_spans";
    public static final String FLUSH_FAILURES = "flush_failures";
    public static final String SHUTDOWN_FAILURES = "shutdown_failures";
    public static final String LAST_EXPORT_DURATION_NANOS = "last_export_duration_nanos";
    public static final String SUPPRESSED_SPANS_EXPORT_DISABLED = "suppressed_spans_export_disabled";
    public static final String EXPORT_ENABLED = "export_enabled";

}
