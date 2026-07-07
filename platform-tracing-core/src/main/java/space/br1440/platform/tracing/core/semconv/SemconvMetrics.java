package space.br1440.platform.tracing.core.semconv;

/**
 * Абстракция метрик semantic-слоя.
 * <p>
 * Модуль {@code platform-tracing-core} намеренно НЕ зависит от Micrometer (инвариант: core
 * работает поверх OTel API + slf4j). Поэтому {@code AttributePolicy} эмитит метрики через эту
 * абстракцию, а конкретную Micrometer-реализацию (счётчики/gauge) поставляет
 * {@code platform-tracing-spring-boot-autoconfigure}.
 * <p>
 * Все теги — low-cardinality (id правила, имя builder'а, класс ключа), без значений атрибутов.
 */
public interface SemconvMetrics {

    /** No-op реализация для сред без метрик (unit-тесты, SDK-only без Micrometer). */
    SemconvMetrics NOOP = new SemconvMetrics() {
    };

    /**
     * Нарушение semconv-контракта.
     *
     * @param ruleId  стабильный id правила (low-cardinality)
     * @param builder имя builder'а/источника (low-cardinality)
     */
    default void violation(String ruleId, String builder) {
        // no-op по умолчанию
    }

    /**
     * Использование escape-hatch {@code unsafeAttribute}.
     *
     * @param keyClass класс ключа: {@code known}/{@code unknown}/{@code rejected} (low-cardinality)
     */
    default void unsafeAttribute(String keyClass) {
        // no-op по умолчанию
    }
}
