package space.br1440.platform.tracing.otel.javaagent.control;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EffectiveTracingPolicyDigestTest {

    @Test
    void matchesGoldenVector() {
        // given
        TracingSettings policy = TracingSettings.of(
                true,
                0.25d,
                Map.of("/orders", 0.5d, "/health", 0.0d), SpanAttributeValidationLevel.WARNING, true, true, AgentLogLevel.INFO);

        // when/then
        assertThat(TracingSettingsDigest.sha256(policy))
                .isEqualTo("8a2b6294d632df6e3536ccb114b33ef51a3e6abe1fc839954a18bb640ad1b9b4");
    }

    @Test
    void matchesGoldenVectorWithEmptyRouteRatios() {
        // given
        TracingSettings policy = TracingSettings.of(
                true,
                0.25d,
                Map.of(), SpanAttributeValidationLevel.WARNING, true, true, AgentLogLevel.INFO);

        // when/then
        assertThat(TracingSettingsDigest.sha256(policy))
                .isEqualTo("29c8ec96198d7db4be9d0a864e0ce814e509d4245c2e91b116506c0544bf4131");
    }

    @Test
    void ignoresInputMapOrderAndNormalizesNegativeZero() {
        // given
        Map<String, Double> first = new LinkedHashMap<>();
        first.put("/z", -0.0d);
        first.put("/a", 0.5d);
        Map<String, Double> second = new LinkedHashMap<>();
        second.put("/a", 0.5d);
        second.put("/z", 0.0d);

        // when
        TracingSettings left = policy(-0.0d, first);
        TracingSettings right = policy(0.0d, second);

        // then
        assertThat(TracingSettingsDigest.sha256(left))
                .isEqualTo(TracingSettingsDigest.sha256(right));
    }

    @Test
    void changesWhenAnyCategoryALeafChanges() {
        // given
        TracingSettings baseline = AcceptedPolicyTestFixtures.policy(0.1d);
        TracingSettings changed = AcceptedPolicyTestFixtures.policy(0.2d);

        // when/then
        assertThat(TracingSettingsDigest.sha256(baseline))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(TracingSettingsDigest.sha256(changed));
    }

    @Test
    void utf8OrderingIsIndependentFromPolicyMapNaturalOrdering() {
        // given
        String supplementary = "\uD800\uDC00";
        String privateUse = "\uE000";
        TracingSettings first = policy(
                0.1d,
                Map.of(supplementary, 0.2d, privateUse, 0.3d));
        Map<String, Double> reversed = new LinkedHashMap<>();
        reversed.put(privateUse, 0.3d);
        reversed.put(supplementary, 0.2d);
        TracingSettings second = policy(0.1d, reversed);

        // when/then
        assertThat(first.samplingRouteRatios().firstKey()).isEqualTo(supplementary);
        assertThat(TracingSettingsDigest.sha256(first))
                .isEqualTo(TracingSettingsDigest.sha256(second));
    }

    private static TracingSettings policy(
            double samplingRatio,
            Map<String, Double> routeRatios) {
        return TracingSettings.of(
                true,
                samplingRatio,
                routeRatios, SpanAttributeValidationLevel.WARNING, true, true, AgentLogLevel.INFO);
    }
}
