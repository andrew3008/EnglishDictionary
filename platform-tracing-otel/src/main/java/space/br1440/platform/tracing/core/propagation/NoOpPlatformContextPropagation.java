package space.br1440.platform.tracing.core.propagation;

import jakarta.annotation.Nonnull;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * No-op реализация {@link PlatformContextPropagation} для сред без активного платформенного фасада трассировки.
 * <p>
 * Все методы возвращают переданный аргумент без изменений. Используется как fallback,
 * когда {@code OpenTelemetry} оказывается NoOp (например, в unit-тестах без подключённого
 * SDK или в окружении без OTel Java Agent).
 */
public final class NoOpPlatformContextPropagation implements PlatformContextPropagation {

    public static final NoOpPlatformContextPropagation INSTANCE = new NoOpPlatformContextPropagation();

    private NoOpPlatformContextPropagation() {
    }

    @Override
    @Nonnull
    public Runnable wrap(@Nonnull Runnable task) {
        return Objects.requireNonNull(task, "task");
    }

    @Override
    @Nonnull
    public <T> ThrowingSupplier<T> wrap(@Nonnull ThrowingSupplier<T> supplier) {
        return Objects.requireNonNull(supplier, "supplier");
    }

    @Override
    @Nonnull
    public Executor contextAware(@Nonnull Executor delegate) {
        return Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    @Nonnull
    public ExecutorService contextAware(@Nonnull ExecutorService delegate) {
        return Objects.requireNonNull(delegate, "delegate");
    }
}
