package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.SpanLinkContext;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.function.Supplier;

/**
 * Common scoped-execution contract for v3 manual semantic builders.
 */
public interface PlatformSpanBuilder<B extends PlatformSpanBuilder<B>> {

    @Nonnull
    B child();

    @Nonnull
    B root();

    @Nonnull
    B detached();

    @Nonnull
    B linkedTo(@Nonnull SpanLinkContext... links);

    @Nonnull
    B fromRemoteContext(@Nonnull String... traceparents);

    @Nonnull
    SpanHandle start();

    void run(@Nonnull Runnable action);

    @Nonnull
    <T> T call(@Nonnull Supplier<T> supplier);

    @Nonnull
    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;

}
