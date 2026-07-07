package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.api.span.builder.InternalSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Policy-backed реализация {@link InternalSpanBuilder} (категория {@link SpanCategory#INTERNAL}).
 * <p>
 * Возвращается {@code DefaultPlatformTracing#internalSpan()}. INTERNAL — единственный kind, не
 * покрытый OTel Java Agent, поэтому создание собственного span'а безопасно (нет double
 * instrumentation).
 */
public final class InternalSpanBuilderImpl extends AbstractPlatformSpanBuilder implements InternalSpanBuilder {

    public InternalSpanBuilderImpl(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                   @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
    }

    @Override
    protected SpanCategory category() {
        return SpanCategory.INTERNAL;
    }

    @Override
    @Nonnull
    public InternalSpanBuilder name(@Nonnull String name) {
        setName(Objects.requireNonNull(name, "name"));
        return this;
    }

    @Override
    @Nonnull
    public <V> InternalSpanBuilder attribute(@Nonnull AttributeKey<V> key, @Nonnull V value) {
        putAttribute(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return this;
    }

    @Override
    @Nonnull
    public <V> InternalSpanBuilder lazyAttribute(@Nonnull AttributeKey<V> key, @Nonnull Supplier<? extends V> valueSupplier) {
        putLazyAttribute(Objects.requireNonNull(key, "key"), Objects.requireNonNull(valueSupplier, "valueSupplier"));
        return this;
    }

    @Override
    @Nonnull
    public InternalSpanBuilder forceNewSpan() {
        markForceNewSpan();
        return this;
    }

    @Override
    @Nonnull
    public SpanScope start() {
        return startSpanInternal();
    }
}
