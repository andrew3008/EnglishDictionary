package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.api.span.builder.PlatformSpanBuilder;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Реализация общего fluent-контракта {@link PlatformSpanBuilder} поверх
 * {@link AbstractPlatformSpanBuilder}: убирает дублирование одинаковых методов
 * ({@code name}/{@code attribute}/{@code lazyAttribute}/{@code unsafeAttribute}/{@code forceNewSpan}/
 * {@code start}) во всех типизированных builder'ах. Конкретный builder добавляет только
 * {@link #category()} и category-specific setter'ы.
 *
 * @param <B> конкретный тип builder'а (self-type) для fluent-цепочки
 */
public abstract class AbstractTypedSpanBuilder<B extends PlatformSpanBuilder<B>>
        extends AbstractPlatformSpanBuilder implements PlatformSpanBuilder<B> {

    protected AbstractTypedSpanBuilder(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                       @Nonnull ExceptionRecorder exceptionRecorder) {
        super(tracer, policy, exceptionRecorder);
    }

    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

    @Override
    @Nonnull
    public B name(@Nonnull String name) {
        setName(Objects.requireNonNull(name, "name"));
        return self();
    }

    @Override
    @Nonnull
    public <V> B attribute(@Nonnull AttributeKey<V> key, @Nonnull V value) {
        putAttribute(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return self();
    }

    @Override
    @Nonnull
    public <V> B lazyAttribute(@Nonnull AttributeKey<V> key, @Nonnull Supplier<? extends V> valueSupplier) {
        putLazyAttribute(Objects.requireNonNull(key, "key"), Objects.requireNonNull(valueSupplier, "valueSupplier"));
        return self();
    }

    @Override
    @Nonnull
    public B unsafeAttribute(@Nonnull String key, @Nonnull String value) {
        putUnsafe(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return self();
    }

    @Override
    @Nonnull
    public B forceNewSpan() {
        markForceNewSpan();
        return self();
    }

    @Override
    @Nonnull
    public SpanScope start() {
        return startSpanInternal();
    }
}
