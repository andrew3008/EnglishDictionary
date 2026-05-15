package space.br1440.platform.metrics.adr.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.lang.Nullable;

/**
 * Вспомогательные методы для поиска метрик в {@link MeterRegistry} в тестах ADR.
 */
public final class MetricsTestSupport {

    private MetricsTestSupport() {
    }

    /**
     * Ищет Counter по имени и парам тегов (key1, value1, key2, value2, ...).
     * Бросает {@link AssertionError} если не найден — удобно для явных утверждений.
     */
    public static Counter findCounter(MeterRegistry registry, String name, String... tagPairs) {
        Counter counter = registry.find(name).tags(tagPairs).counter();
        if (counter == null) {
            throw new AssertionError("Counter не найден: name=" + name + ", tags=" + formatTags(tagPairs)
                    + "\nДоступные метрики: " + registry.getMeters());
        }
        return counter;
    }

    /**
     * Ищет Counter по имени и парам тегов. Возвращает {@code null} если не найден.
     */
    @Nullable
    public static Counter findCounterOrNull(MeterRegistry registry, String name, String... tagPairs) {
        return registry.find(name).tags(tagPairs).counter();
    }

    /**
     * Ищет Timer по имени и парам тегов.
     * Бросает {@link AssertionError} если не найден.
     */
    public static Timer findTimer(MeterRegistry registry, String name, String... tagPairs) {
        Timer timer = registry.find(name).tags(tagPairs).timer();
        if (timer == null) {
            throw new AssertionError("Timer не найден: name=" + name + ", tags=" + formatTags(tagPairs)
                    + "\nДоступные метрики: " + registry.getMeters());
        }
        return timer;
    }

    /**
     * Возвращает значение тега с данным ключом у первой найденной метрики с данным именем,
     * или {@code null} если метрика не существует или тег в ней не присутствует.
     */
    @Nullable
    public static String tagValue(MeterRegistry registry, String metricName, String tagKey) {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(metricName))
                .findFirst()
                .map(m -> m.getId().getTag(tagKey))
                .orElse(null);
    }

    private static String formatTags(String[] tagPairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < tagPairs.length - 1; i += 2) {
            if (i > 0) sb.append(", ");
            sb.append(tagPairs[i]).append("=").append(tagPairs[i + 1]);
        }
        sb.append("}");
        return sb.toString();
    }
}
