package space.br1440.platform.datasource.config.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.configuration.support.ConnectParametersMerger;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConnectParametersMerger")
class DriverParamsMergerTest {

    @Test
    @DisplayName("merge: возвращает новый LinkedHashMap, сохраняющий insertion-order base → extra")
    void merge_preservesInsertionOrder() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("sslmode", "prefer");
        base.put("applicationName", "demo");

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("loadBalanceHosts", "true");

        Map<String, String> merged = ConnectParametersMerger.merge(base, extra);

        assertThat(merged).isInstanceOf(LinkedHashMap.class);
        assertThat(merged.keySet()).containsExactly("sslmode", "applicationName", "loadBalanceHosts");
    }

    @Test
    @DisplayName("merge: значения из extra перекрывают одноимённые ключи из base")
    void merge_extraOverridesBase() {
        Map<String, String> base = Map.of("sslmode", "prefer", "connectTimeout", "10");
        Map<String, String> extra = Map.of("sslmode", "require");

        Map<String, String> merged = ConnectParametersMerger.merge(base, extra);

        assertThat(merged).containsEntry("sslmode", "require");
        assertThat(merged).containsEntry("connectTimeout", "10");
    }

    @Test
    @DisplayName("merge: null-аргументы трактуются как пустые карты")
    void merge_nullArgumentsAreEmpty() {
        assertThat(ConnectParametersMerger.merge(null, null)).isEmpty();
        assertThat(ConnectParametersMerger.merge(null, Map.of("k", "v"))).containsExactly(Map.entry("k", "v"));
        assertThat(ConnectParametersMerger.merge(Map.of("k", "v"), null)).containsExactly(Map.entry("k", "v"));
    }

    @Test
    @DisplayName("merge: возвращаемая карта независима от входных (мутация не отражается)")
    void merge_returnsIndependentMap() {
        Map<String, String> base = new LinkedHashMap<>(Map.of("k", "v"));
        Map<String, String> extra = new LinkedHashMap<>();

        Map<String, String> merged = ConnectParametersMerger.merge(base, extra);
        base.put("injected", "x");
        extra.put("injected2", "y");

        assertThat(merged).doesNotContainKeys("injected", "injected2");
    }
}
