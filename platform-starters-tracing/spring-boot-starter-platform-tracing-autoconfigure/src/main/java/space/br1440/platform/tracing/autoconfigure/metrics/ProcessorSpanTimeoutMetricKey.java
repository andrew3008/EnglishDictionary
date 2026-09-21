package space.br1440.platform.tracing.autoconfigure.metrics;

enum ProcessorSpanTimeoutMetricKey {

    FORCED_SPAN_CLOSES("forced_span_closes"),
    FORCED_TRACE_CLOSES("forced_trace_closes"),
    TRACKING_EVICTED("tracking_evicted"),
    SPANS_REJECTED_PER_TRACE("spans_rejected_per_trace"),
    SPANS_REJECTED_GLOBAL("spans_rejected_global"),
    SPAN_LEDGER_INVARIANT_VIOLATION("span_ledger_invariant_violation");

    private final String metricTag;

    ProcessorSpanTimeoutMetricKey(String metricTag) {
        this.metricTag = metricTag;
    }

    String metricTag() {
        return metricTag;
    }
}
