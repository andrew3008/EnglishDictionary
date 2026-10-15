package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.ApplySnapshotRequestParameters;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PlatformJmxRequestEncoder;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class SpringToAgentWireCodecParityTest {

    private static final JmxControlOpenTypeCodec AGENT_CODEC = new JmxControlOpenTypeCodec();

    @Test
    void expectedBaseAttemptMetadataAndPolicyRoundTripThroughRealAgentDecoder() {
        String agentInstanceId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        DecodedSettingsUpdate decoded = roundTrip(
                agentInstanceId, 42L, attemptId, "CONFIG_SERVER",
                settings(true, 0.42d, Map.of("/checkout", 0.6d, "/search", 0.2d),
                        "WARNING", true, true, "DEBUG"));

        assertThat(decoded.expectedBase()).isEqualTo(
                new space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision(
                        AgentInstanceId.parse(agentInstanceId),
                        42L
                )
        );
        assertThat(decoded.attempt().attemptId()).isEqualTo(attemptId);
        assertThat(decoded.attempt().writer()).isEqualTo(
                space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter.CONFIG_SERVER
        );
        TracingSettings settings = decoded.settings();
        assertThat(settings.samplingEnabled()).isTrue();
        assertThat(settings.samplingRatio()).isEqualTo(0.42d);
        assertThat(settings.samplingRouteRatios()).containsEntry("/checkout", 0.6d).containsEntry("/search", 0.2d);
        assertThat(settings.validationLevel()).isEqualTo(SpanAttributeValidationLevel.WARNING);
        assertThat(settings.exporterEnabled()).isTrue();
        assertThat(settings.traceControlHeadersEnabled()).isTrue();
        assertThat(settings.diagnosticsLogLevel()).isEqualTo(AgentLogLevel.DEBUG);
    }

    @Test
    void emptyRouteRatiosRoundTrip() {
        DecodedSettingsUpdate decoded = roundTrip(
                UUID.randomUUID().toString(), 0L, UUID.randomUUID().toString(), "ACTUATOR",
                settings(false, 0.0d, Map.of(), "DISABLED", false, false, "OFF")
        );

        assertThat(decoded.expectedBase().acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.settings().samplingRouteRatios()).isEmpty();
        assertThat(decoded.attempt().writer()).isEqualTo(
                space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter.ACTUATOR
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOOTSTRAP", "FOO"})
    void unsupportedWriterIsRejectedByRealAgentDecoder(String writer) {
        // given
        CompleteTracingSettings settings =
                settings(false, 0.0d, Map.of(), "DISABLED", false, false, "OFF");

        // when
        // then
        assertThatThrownBy(() -> roundTrip(
                UUID.randomUUID().toString(),
                0L,
                UUID.randomUUID().toString(),
                writer,
                settings
        ))
                .isInstanceOfSatisfying(
                        JmxControlDecodeException.class,
                        exception -> assertThat(exception.messageCode())
                                .isEqualTo(SettingsRejectionReason.INVALID_VALUE)
                );
    }

    @Test
    void agentInstanceIdIsPreservedExactly() {
        String agentInstanceId = UUID.randomUUID().toString();
        DecodedSettingsUpdate decoded = roundTrip(
                agentInstanceId, 5L, UUID.randomUUID().toString(), "CONFIG_SERVER",
                settings(true, 0.5d, Map.of(), "WARNING", true, true, "INFO"));

        assertThat(decoded.expectedBase().agentInstanceId().toString()).isEqualTo(agentInstanceId);
    }

    @ParameterizedTest
    @CsvSource({
            "true, DISABLED, false, false",
            "false, ERROR, false, false",
            "false, WARNING, false, false",
            "false, DISABLED, true, false",
            "false, DISABLED, false, true"
    })
    void singleLeafRoundTripsThroughRealSpringEncoder(boolean samplingEnabled,
                                                      String validationLevel,
                                                      boolean exporterEnabled,
                                                      boolean traceControlHeadersEnabled) {
        assertBooleanOneHot(samplingEnabled, validationLevel, exporterEnabled, traceControlHeadersEnabled);
    }

    private static void assertBooleanOneHot(boolean samplingEnabled, String validationLevel,
                                            boolean exporterEnabled, boolean traceControlHeadersEnabled) {
        DecodedSettingsUpdate decoded = roundTrip(
                UUID.randomUUID().toString(), 1L, UUID.randomUUID().toString(), "CONFIG_SERVER",
                settings(samplingEnabled, 0.5d, Map.of(),
                        validationLevel, exporterEnabled, traceControlHeadersEnabled, "INFO"));

        TracingSettings settings = decoded.settings();
        assertThat(settings.samplingEnabled()).isEqualTo(samplingEnabled);
        assertThat(settings.validationLevel().name()).isEqualTo(validationLevel);
        assertThat(settings.exporterEnabled()).isEqualTo(exporterEnabled);
        assertThat(settings.traceControlHeadersEnabled()).isEqualTo(traceControlHeadersEnabled);
    }

    private static DecodedSettingsUpdate roundTrip(String agentInstanceId, long acceptedSequence,
                                                   String attemptId, String writer,
                                                   CompleteTracingSettings settings) {
        SettingsRevision expectedBase = new SettingsRevision(agentInstanceId, acceptedSequence);

        CompositeData wire = PlatformJmxRequestEncoder.encodeApplyRequest(
                ApplySnapshotRequestParameters.builder()
                        .expectedBase(expectedBase)
                        .attemptId(attemptId)
                        .writer(writer)
                        .policy(settings)
                        .build()
        );

        return AGENT_CODEC.decode(wire);
    }

    private static CompleteTracingSettings settings(boolean samplingEnabled, double samplingRatio,
                                                    Map<String, Double> samplingRouteRatios, String validationLevel,
                                                    boolean exporterEnabled, boolean traceControlHeadersEnabled, String diagnosticsLogLevel) {
        return CompleteTracingSettings.builder()
                .samplingEnabled(samplingEnabled)
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(new LinkedHashMap<>(samplingRouteRatios))
                .validationLevel(validationLevel)
                .exporterEnabled(exporterEnabled)
                .traceControlHeadersEnabled(traceControlHeadersEnabled)
                .diagnosticsLogLevel(diagnosticsLogLevel)
                .build();
    }
}
