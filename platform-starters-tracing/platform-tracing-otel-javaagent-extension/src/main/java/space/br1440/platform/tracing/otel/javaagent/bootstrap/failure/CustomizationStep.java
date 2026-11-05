package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

public enum CustomizationStep {

    TRACE_PIPELINE_ATTESTATION(
            SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED,
            "trace-pipeline-attestation"
    ),
    RESOURCE_INITIALIZATION("RESOURCE_INITIALIZATION_FAILED", "resource-initialization"),
    SENDER_SPI_INSTALLATION("SENDER_SPI_INSTALLATION_FAILED", "sender-spi-installation"),
    PROPAGATION_INITIALIZATION("PROPAGATION_INITIALIZATION_FAILED", "propagation-initialization"),
    SPAN_PROCESSOR_INITIALIZATION("SPAN_PROCESSOR_INITIALIZATION_FAILED", "span-processor-initialization"),
    EXPORTER_INITIALIZATION("EXPORTER_INITIALIZATION_FAILED", "exporter-initialization"),
    EXPORT_PROCESSOR_INITIALIZATION("EXPORT_PROCESSOR_INITIALIZATION_FAILED", "export-processor-initialization");

    private final String failureCode;
    private final String subphase;

    CustomizationStep(String failureCode, String subphase) {
        this.failureCode = failureCode;
        this.subphase = subphase;
    }

    public String failureCode() {
        return failureCode;
    }

    String subphase() {
        return subphase;
    }
}
