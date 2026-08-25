package space.br1440.platform.tracing.autoconfigure.metrics;

enum ExportProcessorMetricKey {

    DROPPED_OVERFLOW("dropped_overflow"),
    DROPPED_AFTER_SHUTDOWN("dropped_after_shutdown"),
    DROPPED_AFTER_FAILURE("dropped_after_failure"),
    DISCARDED_QUEUED("discarded_queued"),
    FAILURES("failures"),
    SHUTDOWN_FAILURES("shutdown_failures"),
    TIMEOUTS("timeouts");

    private final String metricTag;

    ExportProcessorMetricKey(String metricTag) {
        this.metricTag = metricTag;
    }

    String metricTag() {
        return metricTag;
    }
}
