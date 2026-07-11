package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.function.Supplier;

public interface SpecifiedSpan {

    @Nonnull
    SpanHandle start();

    void run(@Nonnull Runnable action);

    @Nonnull
    <T> T call(@Nonnull Supplier<T> supplier);

    @Nonnull
    <T> T callChecked(@Nonnull ThrowingSupplier<T> supplier) throws Exception;

}
