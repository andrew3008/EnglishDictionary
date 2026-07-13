package space.br1440.platform.tracing.api.spi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Правило обнаружения чувствительных данных в значениях атрибутов span'ов.
 * <p>
 * Применяется {@code ScrubbingSpanProcessor} в модуле {@code platform-tracing-otel-extension}
 * до экспорта span'а. Правила выполняются в порядке возрастания {@link #priority()} по модели
 * «накопить и свести» (accumulate-and-merge): расширение OpenTelemetry обходит все применимые правила и сводит их
 * решения по строгости (KEEP &lt; TRUNCATE &lt; HASH &lt; MASK &lt; DROP).
 */
public interface SpanAttributeScrubbingRule {

    /**
     * Уникальное имя правила, отображаемое в метриках и логах при срабатывании.
     */
    @Nonnull
    String name();

    /**
     * Приоритет правила. Меньшее значение означает более высокий приоритет.
     * <p>
     * Зарезервированные диапазоны (контроль соблюдается в PR-3 Priority Hardening):
     * <ul>
     *   <li>{@code 0–99}    — critical built-in правила платформы;</li>
     *   <li>{@code 100–499} — standard built-in правила платформы;</li>
     *   <li>{@code 500–899} — доменные правила;</li>
     *   <li>{@code 900+}    — пользовательские (custom) правила.</li>
     * </ul>
     * Custom-правило с приоритетом ниже 900 будет принудительно поднято (clamp) до 900 с WARN-логом.
     */
    default int priority() {
        return 1000;
    }

    /**
     * Проверяет, следует ли исключить данный ключ атрибута из применения правила.
     *
     * <p>Правило по умолчанию не исключает ни один ключ — переопределите этот метод,
     * если правило должно применяться только к части ключей (например, для
     * regex- или value-based правил, требующих точного соответствия ключа).
     *
     * @param key нормализованный платформой ключ атрибута
     * @return {@code true}, если ключ должен быть исключён из применения правила
     *         (в этом случае {@link #evaluate} не вызывается для данного ключа)
     */
    default boolean isExcluded(@Nonnull String key) {
        return false;
    }

    /**
     * Является ли правило critical built-in правилом платформы.
     * <p>
     * Только critical-правила могут формировать терминальные решения
     * ({@link ScrubbingDecision#terminal()}), которые нельзя ослабить последующими правилами, и
     * только для них при сбое/открытом circuit breaker применяется fail-closed (а не skip).
     * Пользовательские реализации должны оставлять значение по умолчанию {@code false}.
     */
    default boolean critical() {
        return false;
    }

    /**
     * Вычисляет решение по атрибуту. Возвращает {@link ScrubbingDecision#keep()}, если атрибут
     * безопасен (эквивалент «правило не сработало»).
     *
     * @param key   нормализованный платформой ключ атрибута
     * @param value сырое значение атрибута; может быть {@code null}
     * @return решение о действии над значением; никогда {@code null}
     */
    @Nonnull
    ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value);

}
