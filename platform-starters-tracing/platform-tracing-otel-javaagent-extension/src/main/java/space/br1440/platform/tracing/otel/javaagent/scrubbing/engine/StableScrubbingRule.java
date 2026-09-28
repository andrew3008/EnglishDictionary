package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

public final class StableScrubbingRule implements SpanAttributeScrubbingRule {

    private final SpanAttributeScrubbingRule delegate;
    private final Class<?> implementationClass;
    private final String name;
    private final int priority;

    private StableScrubbingRule(SpanAttributeScrubbingRule delegate,
                                Class<?> implementationClass,
                                String name,
                                int priority) {
        this.delegate = delegate;
        this.implementationClass = implementationClass;
        this.name = name;
        this.priority = priority;
    }

    public static StableScrubbingRule captureExternal(SpanAttributeScrubbingRule rule) {
        Objects.requireNonNull(rule, "rule");

        String name = Objects.requireNonNull(rule.name(), "rule.name()");
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("External scrubbing rule name must not be blank");
        }

        if (rule.critical()) {
            throw new IllegalArgumentException("External scrubbing rule must not be critical");
        }

        return new StableScrubbingRule(rule, rule.getClass(), name, rule.priority());
    }

    public Class<?> implementationClass() {
        return implementationClass;
    }

    @Override
    @Nonnull
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isExcluded(@Nonnull String key) {
        return delegate.isExcluded(key);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
        return delegate.evaluate(key, value);
    }
}
