package space.br1440.platform.tracing.otel.sampling.config;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicySnapshotFactory;
import space.br1440.platform.tracing.otel.sampling.model.RouteRatioPrefix;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Эквивалентность и поведение {@link SamplingPolicySnapshotFactory}.
 * <p>
 * Защищает, что компиляция «сырого» конфига в снимок: (1) совпадает с устаревшим
 * {@code SamplingPolicySnapshot.fromConfiguration}; (2) lenient пропускает невалидные route-записи;
 * (3) детерминированно сортирует longest-prefix-first; (4) нормализует drop/force.
 */
class SamplingPolicySnapshotFactoryTest {

    private static SamplingPolicyProperties config(Map<String, Double> routeRatios) {
        return new SamplingPolicyProperties(true, 1.0, List.of(), Set.of(), routeRatios);
    }

    @Test
    void compilesValidEntries_skippingInvalidOnes_inDeterministicOrder() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("/api", 0.10);
        source.put("/api/v2/orders", 1.00);
        source.put("  ", 0.5);
        source.put("/bad", 1.5);

        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFactory.create(config(source));

        assertThat(snapshot.getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/api/v2/orders", "/api");
        assertThat(snapshot.isEnabled()).isTrue();
        assertThat(snapshot.getDefaultRatio()).isEqualTo(1.0);
    }

    @Test
    void lenient_skipsInvalidEntries() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("  ", 0.5);
        source.put("/null", null);
        source.put("/high", 1.5);
        source.put("/neg", -0.1);
        source.put("/ok", 0.25);

        assertThat(SamplingPolicySnapshotFactory.create(config(source)).getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/ok");
    }

    @Test
    void sortsLongestPrefixFirstThenLexicographic() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("/api", 0.10);
        source.put("/api/v2/orders", 1.00);
        source.put("/api/v2", 0.50);

        assertThat(SamplingPolicySnapshotFactory.create(config(source)).getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/api/v2/orders", "/api/v2", "/api");
    }

    @Test
    void normalizesDropAndForceValues() {
        SamplingPolicyProperties config = new SamplingPolicyProperties(
                true, 1.0,
                java.util.Arrays.asList("  /a ", "", " /b"),
                Set.of(" On ", "CUSTOM"),
                Map.of());

        SamplingPolicySnapshot snapshot = SamplingPolicySnapshotFactory.create(config);

        assertThat(snapshot.getDroppedRoutes()).containsExactly("/a", "/b");
        assertThat(snapshot.getForceRecordValues()).containsExactlyInAnyOrder("on", "custom");
    }

    @Test
    void create_rejectsDefaultRatioBelowZero() {
        assertThatThrownBy(() -> SamplingPolicySnapshotFactory.create(
                new SamplingPolicyProperties(true, -0.1, List.of(), Set.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultRatio must be in [0.0, 1.0]");
    }

    @Test
    void create_rejectsDefaultRatioAboveOne() {
        assertThatThrownBy(() -> SamplingPolicySnapshotFactory.create(
                new SamplingPolicyProperties(true, 1.1, List.of(), Set.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultRatio must be in [0.0, 1.0]");
    }

    @Test
    void create_rejectsInvalidDefaultRatioBeforeRouteCompilation() {
        Map<String, Double> routes = new LinkedHashMap<>();
        routes.put("/api", 0.5);

        assertThatThrownBy(() -> SamplingPolicySnapshotFactory.create(
                new SamplingPolicyProperties(true, 1.5, List.of(), Set.of(), routes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultRatio must be in [0.0, 1.0]");
    }

    @Test
    void emptyRouteRatios_yieldEmptyArray() {
        assertThat(SamplingPolicySnapshotFactory.create(config(Map.of())).getRouteRatios()).isEmpty();
        assertThat(SamplingPolicySnapshotFactory.create(config(null)).getRouteRatios()).isEmpty();
    }
}
