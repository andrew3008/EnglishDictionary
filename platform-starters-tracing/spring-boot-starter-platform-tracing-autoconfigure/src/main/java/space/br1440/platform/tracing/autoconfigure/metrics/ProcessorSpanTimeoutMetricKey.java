package space.br1440.platform.tracing.autoconfigure.metrics;

enum ProcessorSpanTimeoutMetricKey {

    FORCED_SPAN_END_ATTEMPTS("forced_span_end_attempts"),
    FORCED_TRACE_END_ATTEMPTS("forced_trace_end_attempts"),
    TRACKING_EVICTED("tracking_evicted"),
    SPANS_REJECTED_PER_TRACE("spans_rejected_per_trace"),
    SPANS_REJECTED_GLOBAL("spans_rejected_global"),
    SPANS_REJECTED_TRACE_CAPACITY("spans_rejected_trace_capacity"),
    SPAN_LEDGER_INVARIANT_VIOLATION("span_ledger_invariant_violation"),
    SCAN_FAILURES("scan_failures"),
    FORCE_END_FAILURES("force_end_failures");

    private final String metricTag;

    ProcessorSpanTimeoutMetricKey(String metricTag) {
        this.metricTag = metricTag;
    }

    String metricTag() {
        return metricTag;
    }
}
