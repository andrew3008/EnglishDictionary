package space.br1440.platform.tracing.semconv.lint;

import java.util.Optional;

/**
 * Контракт правила семантической конвенции, применяемого к одному {@link SpanRecord}.
 * <p>
 * Реализация должна быть детерминированной и потоко-безопасной — линтер допускает параллельную
 * проверку коллекций span'ов.
 */
public interface LintRule {

    /** Уникальный идентификатор правила (используется в отчёте и для подавления). */
    String id();

    /** Краткое описание правила для отчётов. */
    String description();

    /**
     * Проверяет span. Возвращает пустой {@link Optional}, если правило выполнено, либо описание
     * нарушения.
     */
    Optional<LintViolation> apply(SpanRecord span);
}
