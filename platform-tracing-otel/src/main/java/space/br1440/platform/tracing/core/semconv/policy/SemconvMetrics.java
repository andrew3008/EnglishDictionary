package space.br1440.platform.tracing.core.semconv.policy;

/**
 * Абстракция метрик semantic-слоя.
 * <p>
 * Модуль {@code platform-tracing-otel} намеренно не зависит от Micrometer
 * (инвариант: core работает поверх OTel API + slf4j). Поэтому {@code AttributePolicy} эмитит метрики через эту
 * абстракцию, а конкретную Micrometer-реализацию (счётчики/gauge) поставляет
 * {@code platform-tracing-spring-boot-autoconfigure}.
 * <p>
 * Все теги — low-cardinality (id правила, имя builder'а, класс ключа) без значений атрибутов.
 */
public interface SemconvMetrics {

    SemconvMetrics NOOP = new SemconvMetrics() {
    };

    /**
     * Нарушение semconv-контракта.
     */
    default void violation(String ruleId, String builderName) {
    }

    /**
     * Использование escape-hatch {@code unsafeAttribute}.
     */
    default void unsafeAttribute(String keyClass) {
    }
}
