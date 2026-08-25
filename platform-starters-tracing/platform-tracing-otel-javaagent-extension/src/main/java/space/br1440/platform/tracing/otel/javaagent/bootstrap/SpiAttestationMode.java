package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

enum SpiAttestationMode {

    PRODUCTION,
    CHARACTERIZE_EVIDENCE,
    EXACT_EVIDENCE;

    static SpiAttestationMode fromSystemProperty() {
        String raw = System.getProperty(ExtensionPropertyNames.SPI_ATTESTATION_MODE);
        if (StringUtils.isNullOrBlank(raw)) {
            return PRODUCTION;
        }

        String normalized = raw.trim();
        for (SpiAttestationMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }

        throw new SanitizerBootstrapException(
                SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED,
                "Unknown platform.tracing.spi-attestation.mode: " + normalized
        );
    }
}
