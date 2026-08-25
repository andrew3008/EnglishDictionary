package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

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
