package space.br1440.platform.tracing.autoconfigure.metrics;

enum ProcessorCompositeErrorKey {

    TOTAL(null),
    BAGGAGE("baggage"),
    ENRICHMENT("enrichment"),
    SCRUBBING("scrubbing"),
    VALIDATION("validation"),
    CLASSIFICATION("classification"),
    WATCHDOG("watchdog"),
    SPAN_LIMITS_METRICS("span_limits_metrics");

    private final String processorName;

    ProcessorCompositeErrorKey(String processorName) {
        this.processorName = processorName;
    }

    String processorName() {
        return processorName;
    }

    String metricTag() {
        return processorName == null ? "total" : processorName;
    }
}
