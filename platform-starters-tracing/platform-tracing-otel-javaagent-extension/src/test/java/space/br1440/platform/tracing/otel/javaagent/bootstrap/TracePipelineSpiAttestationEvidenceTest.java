package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;

class TracePipelineSpiAttestationEvidenceTest {

    private final AgentLogCapture capture = new AgentLogCapture();

    @BeforeEach
    void installCapture() {
        capture.install();
        capture.applyLevel(AgentLogLevel.ERROR);
    }

    @AfterEach
    void reset() {
        System.clearProperty(ExtensionPropertyNames.SPI_ATTESTATION_MODE);
        TracePipelineSpiAttestation.resetEvidenceMarkerForTests();
        capture.restore();
    }

    @Test
    void characterize_emits_exactly_one_error_marker() {
        // given
        System.setProperty(
                ExtensionPropertyNames.SPI_ATTESTATION_MODE,
                SpiAttestationMode.CHARACTERIZE_EVIDENCE.name());

        // when
        TracePipelineSpiAttestation.attest(100, BootstrapAutoConfigurationCustomizerProvider.class);

        // then
        assertThat(capture.events())
                .filteredOn(event -> AgentEventCodes.BOOT_SPI_ATTESTATION_EVIDENCE.equals(event.eventCode()))
                .hasSize(1)
                .first()
                .extracting(AgentLogEvent::level, AgentLogEvent::message)
                .containsExactly(AgentLogLevel.ERROR, "Trace pipeline SPI attestation evidence");
    }

    @Test
    void exact_on_test_classpath_fails_because_otel_java_agent_set_is_empty() {
        // given
        System.setProperty(
                ExtensionPropertyNames.SPI_ATTESTATION_MODE,
                SpiAttestationMode.EXACT_EVIDENCE.name());

        // when
        // then
        assertThatThrownBy(() -> TracePipelineSpiAttestation.attest(
                100, BootstrapAutoConfigurationCustomizerProvider.class))
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.TRACE_PIPELINE_ATTESTATION_FAILED);
    }
}
