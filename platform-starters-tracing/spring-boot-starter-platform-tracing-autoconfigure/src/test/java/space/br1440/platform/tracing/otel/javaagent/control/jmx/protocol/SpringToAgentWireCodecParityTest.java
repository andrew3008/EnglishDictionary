package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlRequestEncoder;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

/**
 * Real cross-module Spring-to-Agent wire codec parity test (Commit 3, §9.2 из плана
 * {@code wire-field-descriptors}).
 * <p>
 * Deliberately declared в Agent-internal package {@code control.jmx.protocol} (тот же test bridge
 * приём, что и у {@link AgentToSpringWireCodecParityTest} и {@link CandidateDigestAgentParityTest}),
 * чтобы вызвать реальный, package-private Agent decoder
 * {@code PrivateControlOpenTypeCodec.decode(CompositeData)} напрямую — без расширения Agent
 * production API и без ручного дублирования wire-схемы.
 * <p>
 * Flow под тестом для каждого позитивного сценария:
 * <pre>
 *   Spring PrivateControlRequestEncoder.encodeApplyRequest()   [REAL Spring encoder]
 *   -&gt; javax.management.openmbean.CompositeData                [wire]
 *   -&gt; Agent PrivateControlOpenTypeCodec.decode()               [REAL Agent decoder]
 *   -&gt; semantic field-by-field equality
 * </pre>
 * Это направление — обратное {@link AgentToSpringWireCodecParityTest}: там реальный Agent
 * encoder проверяется против реального Spring decoder, здесь — реальный Spring encoder против
 * реального Agent decoder. Оба направления необходимы, поскольку encoder и decoder на каждой
 * стороне независимы и оба переведены на дескрипторы в этом рефакторинге.
 */
class SpringToAgentWireCodecParityTest {

    private static final PrivateControlOpenTypeCodec AGENT_CODEC = new PrivateControlOpenTypeCodec();

    @Test
    void expectedBaseAttemptMetadataDigestAndPolicyRoundTripThroughRealAgentDecoder() {
        DecodedSettingsUpdate decoded = roundTrip(
                UUID.randomUUID().toString(), 42L,
                UUID.randomUUID().toString(), "CONFIG_SERVER", "REFRESH", "revision-abc",
                settings(true, 0.42d, Map.of("/checkout", 0.6d, "/search", 0.2d),
                        true, false, true, true, "DEBUG"));

        assertThat(decoded.expectedBase().runtimeInstanceId()).isNotBlank();
        assertThat(decoded.expectedBase().acceptedSequence()).isEqualTo(42L);
        assertThat(decoded.attempt().sourceAuthority()).isEqualTo(SettingsSourceAuthority.CONFIG_SERVER);
        assertThat(decoded.attempt().applyTrigger()).isEqualTo(SettingsUpdateTrigger.REFRESH);
        assertThat(decoded.attempt().sourceRevision()).isEqualTo("revision-abc");
        TracingSettings settings = decoded.settings();
        assertThat(settings.samplingEnabled()).isTrue();
        assertThat(settings.samplingRatio()).isEqualTo(0.42d);
        assertThat(settings.samplingRouteRatios()).containsEntry("/checkout", 0.6d).containsEntry("/search", 0.2d);
        assertThat(settings.validationEnabled()).isTrue();
        assertThat(settings.validationStrict()).isFalse();
        assertThat(settings.exporterEnabled()).isTrue();
        assertThat(settings.traceControlHeadersEnabled()).isTrue();
        assertThat(settings.diagnosticsLogLevel()).isEqualTo(TracingLogLevel.DEBUG);
    }

    @Test
    void emptyRouteRatiosRoundTrip() {
        DecodedSettingsUpdate decoded = roundTrip(
                UUID.randomUUID().toString(), 0L,
                UUID.randomUUID().toString(), "HELM", "INITIAL", "",
                settings(false, 0.0d, Map.of(), false, false, false, false, "OFF"));

        assertThat(decoded.expectedBase().acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.settings().samplingRouteRatios()).isEmpty();
        assertThat(decoded.attempt().sourceAuthority()).isEqualTo(SettingsSourceAuthority.HELM);
        assertThat(decoded.attempt().applyTrigger()).isEqualTo(SettingsUpdateTrigger.INITIAL);
    }

    @Test
    void runtimeInstanceIdIsPreservedExactly() {
        String runtimeId = UUID.randomUUID().toString();
        DecodedSettingsUpdate decoded = roundTrip(
                runtimeId, 5L, UUID.randomUUID().toString(), "CONFIG_SERVER", "REFRESH", "rev",
                settings(true, 0.5d, Map.of(), true, false, true, true, "INFO"));

        assertThat(decoded.expectedBase().runtimeInstanceId()).isEqualTo(runtimeId);
    }

    // ── one-hot boolean coverage through the REAL Spring encoder ──────────────────

    @Test
    void samplingEnabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(true, false, false, false, false);
    }

    @Test
    void validationEnabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, true, false, false, false);
    }

    @Test
    void validationStrictOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, false, true, false, false);
    }

    @Test
    void exporterEnabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, false, false, true, false);
    }

    @Test
    void traceControlHeadersEnabledOneHotRoundTripsThroughRealSpringEncoder() {
        assertBooleanOneHot(false, false, false, false, true);
    }

    private static void assertBooleanOneHot(boolean samplingEnabled, boolean validationEnabled,
            boolean validationStrict, boolean exporterEnabled, boolean traceControlHeadersEnabled) {
        DecodedSettingsUpdate decoded = roundTrip(
                UUID.randomUUID().toString(), 1L,
                UUID.randomUUID().toString(), "CONFIG_SERVER", "REFRESH", "rev",
                settings(samplingEnabled, 0.5d, Map.of(),
                        validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled, "INFO"));

        TracingSettings settings = decoded.settings();
        assertThat(settings.samplingEnabled()).isEqualTo(samplingEnabled);
        assertThat(settings.validationEnabled()).isEqualTo(validationEnabled);
        assertThat(settings.validationStrict()).isEqualTo(validationStrict);
        assertThat(settings.exporterEnabled()).isEqualTo(exporterEnabled);
        assertThat(settings.traceControlHeadersEnabled()).isEqualTo(traceControlHeadersEnabled);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static DecodedSettingsUpdate roundTrip(String runtimeInstanceId, long acceptedSequence,
            String attemptId, String sourceAuthority, String applyTrigger, String sourceRevision,
            CompleteTracingSettings settings) {
        ExpectedSettingsRevision expectedBase = new ExpectedSettingsRevision(runtimeInstanceId, acceptedSequence);

        CompositeData wire = PrivateControlRequestEncoder.encodeApplyRequest( // REAL Spring encoder
                expectedBase, attemptId, sourceAuthority, applyTrigger, sourceRevision, settings);

        return AGENT_CODEC.decode(wire); // REAL Agent decoder
    }

    private static CompleteTracingSettings settings(boolean samplingEnabled, double samplingRatio,
            Map<String, Double> samplingRouteRatios, boolean validationEnabled, boolean validationStrict,
            boolean exporterEnabled, boolean traceControlHeadersEnabled, String diagnosticsLogLevel) {
        return CompleteTracingSettings.builder()
                .samplingEnabled(samplingEnabled)
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(new LinkedHashMap<>(samplingRouteRatios))
                .validationEnabled(validationEnabled)
                .validationStrict(validationStrict)
                .exporterEnabled(exporterEnabled)
                .traceControlHeadersEnabled(traceControlHeadersEnabled)
                .diagnosticsLogLevel(diagnosticsLogLevel)
                .build();
    }
}
