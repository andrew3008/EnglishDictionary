package space.br1440.platform.tracing.otel.extension.scrubbing;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

/**
 * Базовый класс встроенных правил scrubbing'а: {@link #name()} и {@link #priority()}
 * делегируются enum-дескриптору {@link BuiltInSpanAttributeScrubbingRules}, чтобы имя конфигурации
 * и приоритет first-match хранились в одном месте. Подклассы реализуют только
 * {@link #evaluate}.
 */
abstract class AbstractBuiltInRule implements SpanAttributeScrubbingRule {

    private final BuiltInSpanAttributeScrubbingRules descriptor;

    protected AbstractBuiltInRule(BuiltInSpanAttributeScrubbingRules descriptor) {
        this.descriptor = descriptor;
    }

    @Nonnull
    @Override
    public final String name() {
        return descriptor.configName();
    }

    @Override
    public final int priority() {
        return descriptor.priority();
    }

    @Override
    public final boolean critical() {
        return descriptor.critical();
    }
}
