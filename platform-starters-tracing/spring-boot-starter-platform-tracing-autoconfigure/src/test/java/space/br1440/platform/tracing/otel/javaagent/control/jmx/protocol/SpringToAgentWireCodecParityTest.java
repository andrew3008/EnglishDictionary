package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.ApplySnapshotRequestParameters;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PlatformJmxRequestEncoder;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

/**
 * Проверяет межмодульный паритет транспортного кодека от Spring к агенту.
 * <p>
 * Класс намеренно объявлен во внутреннем пакете агента {@code control.jmx.protocol}, как и
 * {@link AgentToSpringWireCodecParityTest} с
 * {@link TracingSettingsDigestAgentParityTest}. Это позволяет напрямую вызвать
 * пакетный декодер {@code JmxControlOpenTypeCodec.decode(CompositeData)} без расширения
 * производственного API агента и ручного дублирования транспортной схемы.
 * <p>
 * Проверяемый поток данных для каждого позитивного сценария:
 * <pre>
 *   Spring PlatformJmxRequestEncoder.encodeApplyRequest()   [реальный кодировщик Spring]
 *   -&gt; javax.management.openmbean.CompositeData             [транспорт]
 *   -&gt; Agent JmxControlOpenTypeCodec.decode()               [реальный декодер агента]
 *   -&gt; семантическое равенство каждого поля
 * </pre>
 * Это направление обратно {@link AgentToSpringWireCodecParityTest}: тот тест сопоставляет
 * кодировщик агента с декодером Spring, а этот — кодировщик Spring с декодером агента.
 */
class SpringToAgentWireCodecParityTest {

    private static final JmxControlOpenTypeCodec AGENT_CODEC = new JmxControlOpenTypeCodec();

    @Test
    void expectedBaseAttemptMetadataDigestAndPolicyRoundTripThroughRealAgentDecoder() {
        DecodedSettingsUpdate decoded = roundTrip(
                UUID.randomUUID().toString(), 42L,
                UUID.randomUUID().toString(), "CONFIG_SERVER", "REFRESH", "revision-abc",
                settings(true, 0.42d, Map.of("/checkout", 0.6d, "/search", 0.2d),
                        "WARNING", true, true, "DEBUG"));

        assertThat(decoded.expectedBase().agentInstanceId().toString()).isNotBlank();
        assertThat(decoded.expectedBase().acceptedSequence()).isEqualTo(42L);
        assertThat(decoded.attempt().sourceAuthority()).isEqualTo(SettingsSourceAuthority.CONFIG_SERVER);
        assertThat(decoded.attempt().applyTrigger()).isEqualTo(SettingsUpdateTrigger.REFRESH);
        assertThat(decoded.attempt().sourceRevision()).isEqualTo("revision-abc");
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
                UUID.randomUUID().toString(), 0L,
                UUID.randomUUID().toString(), "HELM", "INITIAL", "",
                settings(false, 0.0d, Map.of(), "DISABLED", false, false, "OFF"));

        assertThat(decoded.expectedBase().acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.settings().samplingRouteRatios()).isEmpty();
        assertThat(decoded.attempt().sourceAuthority()).isEqualTo(SettingsSourceAuthority.HELM);
        assertThat(decoded.attempt().applyTrigger()).isEqualTo(SettingsUpdateTrigger.INITIAL);
    }

    @Test
    void agentInstanceIdIsPreservedExactly() {
        String agentInstanceId = UUID.randomUUID().toString();
        DecodedSettingsUpdate decoded = roundTrip(
                agentInstanceId, 5L, UUID.randomUUID().toString(), "CONFIG_SERVER", "REFRESH", "rev",
                settings(true, 0.5d, Map.of(), "WARNING", true, true, "INFO"));

        assertThat(decoded.expectedBase().agentInstanceId().toString()).isEqualTo(agentInstanceId);
    }

    @Test
    void samplingEnabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(true, "DISABLED", false, false);
    }

    @Test
    void validationLevelErrorOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, "ERROR", false, false);
    }

    @Test
    void validationLevelWarningOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, "WARNING", false, false);
    }

    @Test
    void validationLevelDisabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, "DISABLED", false, false);
    }

    @Test
    void exporterEnabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, "DISABLED", true, false);
    }

    @Test
    void traceControlHeadersEnabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, "DISABLED", false, true);
    }

    private static void assertBooleanOneHot(boolean samplingEnabled, String validationLevel,
                                            boolean exporterEnabled, boolean traceControlHeadersEnabled) {
        DecodedSettingsUpdate decoded = roundTrip(
                UUID.randomUUID().toString(), 1L,
                UUID.randomUUID().toString(), "CONFIG_SERVER", "REFRESH", "rev",
                settings(samplingEnabled, 0.5d, Map.of(),
                        validationLevel, exporterEnabled, traceControlHeadersEnabled, "INFO"));

        TracingSettings settings = decoded.settings();
        assertThat(settings.samplingEnabled()).isEqualTo(samplingEnabled);
        assertThat(settings.validationLevel().name()).isEqualTo(validationLevel);
        assertThat(settings.exporterEnabled()).isEqualTo(exporterEnabled);
        assertThat(settings.traceControlHeadersEnabled()).isEqualTo(traceControlHeadersEnabled);
    }

    private static DecodedSettingsUpdate roundTrip(String agentInstanceId, long acceptedSequence,
                                                   String attemptId, String sourceAuthority, String applyTrigger, String sourceRevision,
                                                   CompleteTracingSettings settings) {
        ExpectedSettingsRevision expectedBase = new ExpectedSettingsRevision(agentInstanceId, acceptedSequence);

        CompositeData wire = PlatformJmxRequestEncoder.encodeApplyRequest(
                ApplySnapshotRequestParameters.builder()
                        .expectedBase(expectedBase)
                        .attemptId(attemptId)
                        .sourceAuthority(sourceAuthority)
                        .applyTrigger(applyTrigger)
                        .sourceRevision(sourceRevision)
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
