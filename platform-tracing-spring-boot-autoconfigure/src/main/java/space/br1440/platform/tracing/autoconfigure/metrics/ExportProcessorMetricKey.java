package space.br1440.platform.tracing.autoconfigure.metrics;

enum ExportProcessorMetricKey {

    DROPPED_OVERFLOW("dropped_overflow"),
    DROPPED_AFTER_SHUTDOWN("dropped_after_shutdown"),
    FAILURES("failures"),
    TIMEOUTS("timeouts");

    private final String metricTag;

    ExportProcessorMetricKey(String metricTag) {
        this.metricTag = metricTag;
    }

    String metricTag() {
        return metricTag;
    }
}
