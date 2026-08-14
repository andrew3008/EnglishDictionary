package space.br1440.platform.tracing.otel.propagation;

import io.opentelemetry.context.Context;
import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Реализация {@link PlatformContextPropagation} поверх OpenTelemetry Context API.
 * <p>
 * Делегирует переносы в {@code io.opentelemetry.context.Context}:
 * <ul>
 *   <li>{@code wrap(Runnable)} — {@code Context.current().wrap(task)}: snapshot контекста
 *       снимается в момент вызова {@code wrap(...)} (caller-thread) и восстанавливается
 *       на время выполнения задачи в worker-thread.</li>
 *   <li>{@code wrap(ThrowingSupplier)} — ручная композиция через {@code Context.makeCurrent()}
 *       в блоке try-with-resources, поскольку OTel Context не предоставляет встроенного
 *       {@code wrap(Supplier)}.</li>
 *   <li>{@code contextAware(Executor/ExecutorService)} — {@code Context.taskWrapping(...)}:
 *       контекст снимается в момент каждого {@code execute / submit}, что корректно для
 *       long-lived пулов с разными caller'ами.</li>
 * </ul>
 */
public class OtelPlatformContextPropagation implements PlatformContextPropagation {

    @Override
    @Nonnull
    public Runnable wrap(@Nonnull Runnable task) {
        Objects.requireNonNull(task, "task");
        return Context.current().wrap(task);
    }

    @Override
    @Nonnull
    public <T> ThrowingSupplier<T> wrap(@Nonnull ThrowingSupplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");

        Context captured = Context.current();
        return () -> {
            try (var ignored = captured.makeCurrent()) {
                return supplier.get();
            }
        };
    }

    @Override
    @Nonnull
    public Executor contextAware(@Nonnull Executor delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return Context.taskWrapping(delegate);
    }

    @Override
    @Nonnull
    public ExecutorService contextAware(@Nonnull ExecutorService delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return Context.taskWrapping(delegate);
    }
}
