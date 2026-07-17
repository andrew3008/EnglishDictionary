package space.br1440.platform.tracing.api.span.builder;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.function.Supplier;

/**
 * Базовый интерфейс всех span-builder'ов платформы.
 */
public interface ManualSpanBuilder<B extends ManualSpanBuilder<B>> {

    @Nonnull
    B child();

    @Nonnull
    B root();

    @Nonnull
    B detached();

    @Nonnull
    B linkedTo(@Nonnull RemoteSpanLink... links);

    @Nonnull
    B fromTraceparent(@Nonnull String... traceparents);

    @Nonnull
    SpanHandle start();

    void run(@Nonnull Runnable action);

    @Nonnull
    <T> T call(@Nonnull Supplier<T> supplier);

    @Nonnull
    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;

}
