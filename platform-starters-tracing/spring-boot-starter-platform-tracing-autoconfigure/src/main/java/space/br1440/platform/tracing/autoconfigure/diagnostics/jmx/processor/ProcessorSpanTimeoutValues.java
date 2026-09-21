package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

public record ProcessorSpanTimeoutValues(long forcedSpanCloses,
                                      long forcedTraceCloses,
                                      int activeSpanCount,
                                      int activeTraceCount,
                                      int trackedTraceCount,
                                      int trackedTraceCapacity,
                                      long trackingEvictedTotal,
                                      int trackedSpanCapacity,
                                      int trackedSpanPerTraceCapacity,
                                      long spansRejectedPerTraceTotal,
                                      long spansRejectedGlobalTotal,
                                      long spanLedgerInvariantViolationTotal) {
}
