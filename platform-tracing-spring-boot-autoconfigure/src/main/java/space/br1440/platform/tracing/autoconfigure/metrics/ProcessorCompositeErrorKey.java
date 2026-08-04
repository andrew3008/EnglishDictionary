package space.br1440.platform.tracing.autoconfigure.metrics;

enum ProcessorCompositeErrorKey {

    TOTAL(null),
    SPAN_WATCHDOG_PROCESSOR("SpanWatchdogProcessor"),
    PLATFORM_COMPOSITE_SPAN_PROCESSOR("PlatformCompositeSpanProcessor"),
    METRICS_SPAN_PROCESSOR("MetricsSpanProcessor"),
    SCRUBBING_SPAN_PROCESSOR("ScrubbingSpanProcessor"),
    VALIDATING_SPAN_PROCESSOR("ValidatingSpanProcessor"),
    PLATFORM_DROP_OLDEST_EXPORT_SPAN_PROCESSOR("PlatformDropOldestExportSpanProcessor");

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
