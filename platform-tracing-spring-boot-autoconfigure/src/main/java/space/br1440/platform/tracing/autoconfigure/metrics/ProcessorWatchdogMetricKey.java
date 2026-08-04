package space.br1440.platform.tracing.autoconfigure.metrics;

enum ProcessorWatchdogMetricKey {

    FORCED_SPAN_CLOSES("forced_span_closes"),
    FORCED_TRACE_CLOSES("forced_trace_closes");

    private final String metricTag;

    ProcessorWatchdogMetricKey(String metricTag) {
        this.metricTag = metricTag;
    }

    String metricTag() {
        return metricTag;
    }
}
