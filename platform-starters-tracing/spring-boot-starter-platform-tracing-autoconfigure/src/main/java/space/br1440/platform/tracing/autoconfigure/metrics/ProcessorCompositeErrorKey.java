package space.br1440.platform.tracing.autoconfigure.metrics;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

enum ProcessorCompositeErrorKey {

    TOTAL(null),
    BAGGAGE(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_BAGGAGE),
    ENRICHMENT(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_ENRICHMENT),
    REQUEST_ID_PROPAGATION(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_REQUEST_ID_PROPAGATION),
    SCRUBBING(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_SCRUBBING),
    VALIDATION(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_VALIDATION),
    CLASSIFICATION(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_CLASSIFICATION),
    SPAN_TIMEOUT(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_SPAN_TIMEOUT),
    SPAN_LIMITS_METRICS(JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_SPAN_LIMITS_METRICS);

    private final String processorName;

    ProcessorCompositeErrorKey(String processorName) {
        this.processorName = processorName;
    }

    String processorName() {
        return processorName;
    }

    String metricTag() {
        return (processorName == null) ? "total" : processorName;
    }
}
