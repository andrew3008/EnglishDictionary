package space.br1440.platform.tracing.api.spi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Правило обнаружения и маскирования чувствительных данных в значениях атрибутов span'ов.
 * <p>
 * Реализации регистрируются как Spring-бины и применяются {@code ScrubbingSpanProcessor}
 * (модуль {@code platform-tracing-otel-extension}) до экспорта span'а. Правила выполняются
 * в порядке возрастания {@link #priority()}; первое сматчившееся правило определяет результат.
 * <p>
 * Реализация должна быть детерминированной, потоко-безопасной и не блокирующей.
 */
public interface SensitiveDataRule {

    /**
     * Уникальное имя правила, отображаемое в метриках и логах при срабатывании.
     */
    @Nonnull
    String name();

    /**
     * Приоритет правила. Меньшее значение означает более высокий приоритет.
     */
    default int priority() {
        return 1000;
    }

    /**
     * Проверяет, содержит ли значение чувствительные данные.
     */
    boolean matches(@Nonnull String attributeKey, @Nullable String attributeValue);

    /**
     * Возвращает маскированное значение. Контракт: не должен возвращать {@code null}; для полного удаления
     * значения следует вернуть пустую строку.
     */
    @Nonnull
    String mask(@Nonnull String attributeKey, @Nonnull String attributeValue);
}
