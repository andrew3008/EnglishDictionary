package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EffectiveTracingPolicyTest {

    @Test
    void declaresExactlyEightCategoryALeaves() {
        assertThat(EffectiveTracingPolicy.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "samplingEnabled",
                        "samplingRatio",
                        "samplingRouteRatios",
                        "validationEnabled",
                        "validationStrict",
                        "exporterEnabled",
                        "traceControlHeadersEnabled",
                        "diagnosticsLogLevel");
    }

    @Test
    void defensivelyCopiesAndSortsRouteRatios() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("/z", 0.9d);
        source.put("/a", 0.1d);

        EffectiveTracingPolicy policy = policy(source);
        source.put("/later", 0.7d);

        assertThat(policy.samplingRouteRatios())
                .containsExactly(
                        Map.entry("/a", 0.1d),
                        Map.entry("/z", 0.9d));
        assertThatThrownBy(() -> policy.samplingRouteRatios().put("/forbidden", 0.2d))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizesNegativeZeroAcrossAllRatios() {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true,
                -0.0d,
                Map.of("/zero", -0.0d),
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);

        assertThat(Double.doubleToLongBits(policy.samplingRatio()))
                .isEqualTo(Double.doubleToLongBits(0.0d));
        assertThat(Double.doubleToLongBits(policy.samplingRouteRatios().get("/zero")))
                .isEqualTo(Double.doubleToLongBits(0.0d));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeRatios() {
        assertThatThrownBy(() -> policy(Map.of("/route", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(Map.of("/route", Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(Map.of("/route", 1.01d)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesRouteCollectionAndKeyBounds() {
        Map<String, Double> tooMany = new LinkedHashMap<>();
        for (int index = 0; index <= EffectiveTracingPolicyValidator.MAX_ROUTE_ENTRIES; index++) {
            tooMany.put("/route/" + index, 0.5d);
        }

        assertThatThrownBy(() -> policy(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 100");
        assertThatThrownBy(() -> policy(Map.of("x".repeat(256), 0.5d)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 255");
        assertThatThrownBy(() -> policy(Map.of(" \t", 0.5d)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(Map.of("/orders\u0000admin", 0.5d)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EffectiveTracingPolicy policy(Map<String, Double> routeRatios) {
        return EffectiveTracingPolicy.of(
                true,
                0.5d,
                routeRatios,
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);
    }
}
