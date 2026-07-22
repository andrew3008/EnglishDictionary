package space.br1440.platform.tracing.core.sampling.config;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicySnapshotFactory;
import space.br1440.platform.tracing.core.sampling.model.RouteRatioPrefix;
import space.br1440.platform.tracing.core.sampling.model.SamplingPolicySnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LENIENT-матрица compile-пути (silent-skip невалидных route-записей).
 * <p>
 * Закрепляет, что путь компиляции снимка из «сырой» конфигурации НЕ бросает исключения, а молча
 * пропускает невалидные записи route-ratio (пустой/null ключ, null значение, ratio вне [0,1]).
 * Это намеренно отличается от fail-fast пути runtime-обновления (см. SamplerPolicyUpdateTest в
 * otel-extension). Эти две семантики разделены и не должны быть объединены.
 */
class SamplingPolicyPropertiesLenientTest {

    private static SamplingPolicySnapshot compile(Map<String, Double> routeRatios) {
        return SamplingPolicySnapshotFactory.create(
                new SamplingPolicyProperties(true, 1.0, List.of(), Set.of(), routeRatios));
    }

    @Test
    void skipsBlankKey() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("   ", 0.5);
        source.put("/ok", 0.5);

        assertThat(compile(source).getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/ok");
    }

    @Test
    void skipsNullValue() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("/null", null);
        source.put("/ok", 0.5);

        assertThat(compile(source).getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/ok");
    }

    @Test
    void skipsRatioAboveOne() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("/high", 1.5);
        source.put("/ok", 0.5);

        assertThat(compile(source).getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/ok");
    }

    @Test
    void skipsRatioBelowZero() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("/neg", -0.1);
        source.put("/ok", 0.5);

        assertThat(compile(source).getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactly("/ok");
    }

    @Test
    void allInvalid_yieldsEmptyWithoutThrowing() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("  ", 0.5);
        source.put("/neg", -1.0);
        source.put("/null", null);

        assertThat(compile(source).getRouteRatios()).isEmpty();
    }

    @Test
    void boundaryRatios_zeroAndOne_areKept() {
        Map<String, Double> source = new LinkedHashMap<>();
        source.put("/zero", 0.0);
        source.put("/one", 1.0);

        assertThat(compile(source).getRouteRatios())
                .extracting(RouteRatioPrefix::prefix)
                .containsExactlyInAnyOrder("/zero", "/one");
    }
}
