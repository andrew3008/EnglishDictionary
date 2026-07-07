package space.br1440.platform.tracing.otel.extension.scrubbing;

import space.br1440.platform.tracing.api.spi.SensitiveDataRule;

/**
 * Базовый класс встроенных правил scrubbing'а: {@link #name()} и {@link #priority()}
 * делегируются enum-дескриптору {@link BuiltInSensitiveDataRules}, чтобы имя конфигурации
 * и приоритет first-match хранились в одном месте. Подклассы реализуют только
 * {@link #evaluate}.
 */
abstract class AbstractBuiltInRule implements SensitiveDataRule {

    private final BuiltInSensitiveDataRules descriptor;

    protected AbstractBuiltInRule(BuiltInSensitiveDataRules descriptor) {
        this.descriptor = descriptor;
    }

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
