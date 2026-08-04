package space.br1440.platform.tracing.autoconfigure.metrics;

enum ProcessorMetricsMetricKey {

    DROPPED_ATTRIBUTES("dropped_attributes"),
    DROPPED_EVENTS("dropped_events"),
    DROPPED_LINKS("dropped_links");

    private final String metricTag;

    ProcessorMetricsMetricKey(String metricTag) {
        this.metricTag = metricTag;
    }

    String metricTag() {
        return metricTag;
    }
}
