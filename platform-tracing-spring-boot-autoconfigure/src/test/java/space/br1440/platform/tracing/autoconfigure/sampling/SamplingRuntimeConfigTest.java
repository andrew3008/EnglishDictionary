package space.br1440.platform.tracing.autoconfigure.sampling;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingRuntimeConfigTest {

    @Test
    void from_извлекает_Category_A_поля() {
        TracingProperties.Sampling sampling = new TracingProperties.Sampling()
                .setEnabled(false)
                .setRatio(0.25d);
        LinkedHashMap<String, Double> routeRatios = new LinkedHashMap<>();
        routeRatios.put("/api", 0.5d);
        routeRatios.put("/v2", 0.1d);
        sampling.setRouteRatios(routeRatios);

        SamplingRuntimeConfig config = SamplingRuntimeConfig.from(sampling);

        assertThat(config.enabled()).isFalse();
        assertThat(config.defaultRatio()).isEqualTo(0.25d);
        assertThat(config.routeRatioPrefixes()).containsExactly("/api", "/v2");
        assertThat(config.routeRatioValues()).containsExactly(0.5d, 0.1d);
    }

    @Test
    void from_сохраняет_порядок_insertion_order_routeRatios() {
        LinkedHashMap<String, Double> routeRatios = new LinkedHashMap<>();
        routeRatios.put("/z", 0.3d);
        routeRatios.put("/a", 0.7d);
        TracingProperties.Sampling sampling = new TracingProperties.Sampling()
                .setRouteRatios(routeRatios);

        SamplingRuntimeConfig config = SamplingRuntimeConfig.from(sampling);

        assertThat(config.routeRatioPrefixes()).containsExactly("/z", "/a");
        assertThat(config.routeRatioValues()).containsExactly(0.3d, 0.7d);
    }

    @Test
    void from_пустая_карта_routeRatios() {
        TracingProperties.Sampling sampling = new TracingProperties.Sampling()
                .setRouteRatios(null);

        SamplingRuntimeConfig config = SamplingRuntimeConfig.from(sampling);

        assertThat(config.routeRatioPrefixes()).isEmpty();
        assertThat(config.routeRatioValues()).isEmpty();
    }

    @Test
    void source_константа_spring_runtime_config() {
        assertThat(SamplingRuntimeConfig.SOURCE).isEqualTo("spring-runtime-config");
    }
}
