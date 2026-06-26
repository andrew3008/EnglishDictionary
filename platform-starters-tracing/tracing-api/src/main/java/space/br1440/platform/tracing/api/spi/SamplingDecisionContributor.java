package space.br1440.platform.tracing.api.spi;

import jakarta.annotation.Nonnull;

import java.util.Map;
import java.util.Optional;

/**
 * Точка расширения для влияния на решение Sampler'а перед применением вероятностного отбора.
 * <p>
 * Реализации регистрируются как Spring-бины и опрашиваются {@code CompositeSampler}
 * (модуль {@code platform-tracing-otel-extension}) в порядке возрастания {@link #priority()}.
 * Первый contributor, вернувший непустое значение {@link Decision}, определяет окончательное решение.
 * <p>
 * Если ни один contributor не вернул решения, sampler применяет вероятностную политику,
 * настроенную через {@code platform.tracing.sampling.ratio}.
 */
public interface SamplingDecisionContributor {

    /**
     * Приоритет contributor'а. Меньшее значение — раньше срабатывает.
     */
    default int priority() {
        return 1000;
    }

    /**
     * Принимает решение о записи trace'а на основании контекста запуска span'а.
     *
     * @param spanName имя стартующего span'а
     * @param attributes атрибуты, доступные на момент принятия решения (HTTP-заголовки, имена ресурсов)
     * @return непустое значение, если contributor желает зафиксировать решение; иначе пустое значение
     */
    @Nonnull
    Optional<Decision> decide(@Nonnull String spanName, @Nonnull Map<String, String> attributes);

    enum Decision {
        /** Принудительно записать trace. */
        RECORD,
        /** Принудительно отбросить trace. */
        DROP
    }
}
