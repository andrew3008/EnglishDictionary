package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpiAttestationModeTest {

    @AfterEach
    void clearMode() {
        System.clearProperty(ExtensionPropertyNames.SPI_ATTESTATION_MODE);
    }

    @Test
    void blank_property_is_production() {
        // given
        System.clearProperty(ExtensionPropertyNames.SPI_ATTESTATION_MODE);

        // when
        SpiAttestationMode mode = SpiAttestationMode.fromSystemProperty();

        // then
        assertThat(mode).isEqualTo(SpiAttestationMode.PRODUCTION);
    }

    @Test
    void unknown_mode_fails_closed() {
        // given
        System.setProperty(ExtensionPropertyNames.SPI_ATTESTATION_MODE, "ALLOW_ALL");

        // when
        // then
        assertThatThrownBy(SpiAttestationMode::fromSystemProperty)
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }
}
