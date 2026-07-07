package space.br1440.platform.tracing.autoconfigure.sampling;

import java.util.Map;

/**
 * Конвертация {@code routeRatios} Spring-конфигурации в параллельные JMX-массивы
 * ({@code routeRatioPrefixes[]}, {@code routeRatioValues[]}).
 * <p>
 * Порядок элементов — порядок итерации исходной {@link java.util.LinkedHashMap}
 * (дефолт {@link space.br1440.platform.tracing.autoconfigure.TracingProperties.Sampling#routeRatios}).
 * Это сохраняет текущую семантику сопоставления префиксов без trie/longest-prefix-wins.
 * TODO(PR-6E+): longest-prefix-wins ADR — отдельная фаза; порядок может стать нормативным.
 */
final class SamplingRouteRatiosWire {

    record WireArrays(String[] prefixes, double[] values) {
    }

    private SamplingRouteRatiosWire() {
    }

    static WireArrays fromMap(Map<String, Double> routeRatios) {
        if (routeRatios == null || routeRatios.isEmpty()) {
            return new WireArrays(new String[0], new double[0]);
        }
        String[] prefixes = routeRatios.keySet().toArray(new String[0]);
        double[] values = new double[prefixes.length];
        for (int i = 0; i < prefixes.length; i++) {
            values[i] = routeRatios.get(prefixes[i]);
        }
        return new WireArrays(prefixes, values);
    }
}
