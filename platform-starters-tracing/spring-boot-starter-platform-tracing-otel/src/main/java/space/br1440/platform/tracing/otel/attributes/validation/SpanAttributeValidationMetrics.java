package space.br1440.platform.tracing.otel.attributes.validation;

/**
 * Абстракция метрик проверки атрибутов span'а.
 * <p>
 * Модуль {@code platform-tracing-otel} намеренно не зависит от Micrometer
 * (инвариант: core работает поверх OTel API + slf4j). Поэтому {@code SpanAttributeValidator}
 * эмитит метрики через эту абстракцию, а конкретную Micrometer-реализацию поставляет
 * autoconfigure-модуль.
 * <p>
 * Все теги — low-cardinality (id правила, имя builder'а) без значений атрибутов.
 */
@FunctionalInterface
public interface SpanAttributeValidationMetrics {

    SpanAttributeValidationMetrics NOOP = (ruleId, builderName) -> {
    };

    /**
     * Нарушение платформенного контракта атрибутов.
     */
    void violation(String ruleId, String builderName);
}
