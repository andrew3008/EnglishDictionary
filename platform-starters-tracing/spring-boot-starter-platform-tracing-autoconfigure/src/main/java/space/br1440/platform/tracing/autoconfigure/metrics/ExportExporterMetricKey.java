package space.br1440.platform.tracing.autoconfigure.metrics;

enum ExportExporterMetricKey {

    EXPORT_BATCHES("export_batches"),
    EXPORT_BATCH_FAILURES("export_batch_failures"),
    EXPORTED_SPANS("exported_spans"),
    TRANSPORT_DROPPED_SPANS("transport_dropped_spans"),
    FLUSH_FAILURES("flush_failures"),
    SHUTDOWN_FAILURES("shutdown_failures"),
    SUPPRESSED_SPANS_EXPORT_DISABLED("suppressed_spans_export_disabled");

    private final String wireKey;

    ExportExporterMetricKey(String wireKey) {
        this.wireKey = wireKey;
    }

    String wireKey() {
        return wireKey;
    }
}
