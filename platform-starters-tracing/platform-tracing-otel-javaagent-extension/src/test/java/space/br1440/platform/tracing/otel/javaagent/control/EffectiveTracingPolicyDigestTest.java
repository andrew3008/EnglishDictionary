package space.br1440.platform.tracing.otel.javaagent.control;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EffectiveTracingPolicyDigestTest {

    @Test
    void matchesGoldenVector() {
        TracingSettings policy = TracingSettings.of(
                true,
                0.25d,
                Map.of("/orders", 0.5d, "/health", 0.0d),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);

        assertThat(TracingSettingsDigest.sha256(policy))
                .isEqualTo("32f8d43cea97ff26a36b47374314ff8df8d14366e6449a3fe89699d963afdaf1");
    }

    @Test
    void matchesGoldenVectorWithEmptyRouteRatios() {
        TracingSettings policy = TracingSettings.of(
                true,
                0.25d,
                Map.of(),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);

        assertThat(TracingSettingsDigest.sha256(policy))
                .isEqualTo("5c7749beaa1333762487aafe8103a92f68c2757186aa944b756025a0696c2993");
    }

    @Test
    void ignoresInputMapOrderAndNormalizesNegativeZero() {
        Map<String, Double> first = new LinkedHashMap<>();
        first.put("/z", -0.0d);
        first.put("/a", 0.5d);
        Map<String, Double> second = new LinkedHashMap<>();
        second.put("/a", 0.5d);
        second.put("/z", 0.0d);

        TracingSettings left = policy(-0.0d, first);
        TracingSettings right = policy(0.0d, second);

        assertThat(TracingSettingsDigest.sha256(left))
                .isEqualTo(TracingSettingsDigest.sha256(right));
    }

    @Test
    void changesWhenAnyCategoryALeafChanges() {
        TracingSettings baseline = AcceptedPolicyTestFixtures.policy(0.1d);
        TracingSettings changed = AcceptedPolicyTestFixtures.policy(0.2d);

        assertThat(TracingSettingsDigest.sha256(baseline))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(TracingSettingsDigest.sha256(changed));
    }

    @Test
    void utf8OrderingIsIndependentFromPolicyMapNaturalOrdering() {
        String supplementary = "\uD800\uDC00";
        String privateUse = "\uE000";
        TracingSettings first = policy(
                0.1d,
                Map.of(supplementary, 0.2d, privateUse, 0.3d));
        Map<String, Double> reversed = new LinkedHashMap<>();
        reversed.put(privateUse, 0.3d);
        reversed.put(supplementary, 0.2d);
        TracingSettings second = policy(0.1d, reversed);

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
                routeRatios,
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);
    }
}
