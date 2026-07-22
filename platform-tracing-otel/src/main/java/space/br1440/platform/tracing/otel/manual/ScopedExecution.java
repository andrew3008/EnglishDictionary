package space.br1440.platform.tracing.otel.manual;

import jakarta.annotation.Nonnull;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.Objects;
import java.util.function.Supplier;

@UtilityClass
final class ScopedExecution {

    static void run(@Nonnull Supplier<SpanHandle> handleSupplier, @Nonnull Runnable action) {
        Objects.requireNonNull(handleSupplier, "handleSupplier");
        Objects.requireNonNull(action, "action");

        try (SpanHandle handle = handleSupplier.get()) {
            try {
                action.run();
            } catch (RuntimeException e) {
                handle.recordException(e);
                throw e;
            }
        }
    }

    @Nonnull
    static <T> T call(@Nonnull Supplier<SpanHandle> handleSupplier, @Nonnull Supplier<T> supplier) {
        Objects.requireNonNull(handleSupplier, "handleSupplier");
        Objects.requireNonNull(supplier, "supplier");

        try (SpanHandle handle = handleSupplier.get()) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                handle.recordException(e);
                throw e;
            }
        }
    }

    @Nonnull
    static <T> T callChecked(@Nonnull Supplier<SpanHandle> handleSupplier,
                               @Nonnull ThrowingSupplier<T> supplier) throws Exception {
        Objects.requireNonNull(handleSupplier, "handleSupplier");
        Objects.requireNonNull(supplier, "supplier");

        try (SpanHandle handle = handleSupplier.get()) {
            try {
                return supplier.get();
            } catch (Exception e) {
                handle.recordException(e);
                throw e;
            }
        }
    }
}
