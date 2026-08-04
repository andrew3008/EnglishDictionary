package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

public record ProcessorMetricsValues(long droppedAttributesTotal,
                                     long droppedEventsTotal,
                                     long droppedLinksTotal) {
}
