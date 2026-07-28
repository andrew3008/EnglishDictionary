package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EffectiveTracingPolicyDigestTest {

    @Test
    void matchesGoldenVector() {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true,
                0.25d,
                Map.of("/orders", 0.5d, "/health", 0.0d),
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);

        assertThat(EffectiveTracingPolicyDigest.sha256(policy))
                .isEqualTo("32f8d43cea97ff26a36b47374314ff8df8d14366e6449a3fe89699d963afdaf1");
    }

    @Test
    void ignoresInputMapOrderAndNormalizesNegativeZero() {
        Map<String, Double> first = new LinkedHashMap<>();
        first.put("/z", -0.0d);
        first.put("/a", 0.5d);
        Map<String, Double> second = new LinkedHashMap<>();
        second.put("/a", 0.5d);
        second.put("/z", 0.0d);

        EffectiveTracingPolicy left = policy(-0.0d, first);
        EffectiveTracingPolicy right = policy(0.0d, second);

        assertThat(EffectiveTracingPolicyDigest.sha256(left))
                .isEqualTo(EffectiveTracingPolicyDigest.sha256(right));
    }

    @Test
    void changesWhenAnyCategoryALeafChanges() {
        EffectiveTracingPolicy baseline = AcceptedPolicyTestFixtures.policy(0.1d);
        EffectiveTracingPolicy changed = AcceptedPolicyTestFixtures.policy(0.2d);

        assertThat(EffectiveTracingPolicyDigest.sha256(baseline))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(EffectiveTracingPolicyDigest.sha256(changed));
    }

    @Test
    void utf8OrderingIsIndependentFromPolicyMapNaturalOrdering() {
        String supplementary = "\uD800\uDC00";
        String privateUse = "\uE000";
        EffectiveTracingPolicy first = policy(
                0.1d,
                Map.of(supplementary, 0.2d, privateUse, 0.3d));
        Map<String, Double> reversed = new LinkedHashMap<>();
        reversed.put(privateUse, 0.3d);
        reversed.put(supplementary, 0.2d);
        EffectiveTracingPolicy second = policy(0.1d, reversed);

        assertThat(first.samplingRouteRatios().firstKey()).isEqualTo(supplementary);
        assertThat(EffectiveTracingPolicyDigest.sha256(first))
                .isEqualTo(EffectiveTracingPolicyDigest.sha256(second));
    }

    private static EffectiveTracingPolicy policy(
            double samplingRatio,
            Map<String, Double> routeRatios) {
        return EffectiveTracingPolicy.of(
                true,
                samplingRatio,
                routeRatios,
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);
    }
}
