package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;

/**
 * Micrometer-реализация {@link SemconvMetrics} (модуль core от Micrometer не зависит — реализация
 * живёт здесь).
 * <ul>
 *   <li>{@code platform.tracing.semconv.violations{rule, builder}} — нарушения контракта;</li>
 *   <li>{@code platform.tracing.unsafe_attributes{key_class}} — использование escape-hatch.</li>
 * </ul>
 * Все теги low-cardinality (без значений атрибутов). Счётчики кэшируются самим
 * {@link MeterRegistry} по идентификатору метрики.
 */
public final class MicrometerSemconvMetrics implements SemconvMetrics {

    private static final String VIOLATIONS = "platform.tracing.semconv.violations";
    private static final String UNSAFE = "platform.tracing.unsafe_attributes";

    private final MeterRegistry registry;

    public MicrometerSemconvMetrics(@Nonnull MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void violation(String ruleId, String builderName) {
        registry.counter(VIOLATIONS, "rule", ruleId, "builder", builderName).increment();
    }

    @Override
    public void unsafeAttribute(String keyClass) {
        registry.counter(UNSAFE, "key_class", keyClass).increment();
    }
}
