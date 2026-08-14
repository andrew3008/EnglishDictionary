package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

public record ProcessorWatchdogValues(long forcedSpanCloses,
                                      long forcedTraceCloses,
                                      int activeSpanCount,
                                      int activeTraceCount) {
}
