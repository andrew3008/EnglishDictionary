package space.br1440.platform.tracing.otel.propagation;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;
import space.br1440.platform.tracing.otel.mdc.IdentityMdcProjection;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Реализация {@link PlatformContextPropagation} поверх OpenTelemetry Context API.
 * <p>
 * Переносит {@code io.opentelemetry.context.Context} и вместе с ним полную MDC-проекцию
 * (span-ключи и identity-ключи requestId/correlationId, см. {@link IdentityMdcProjection}):
 * <ul>
 *   <li>{@code wrap(Runnable)} и {@code wrap(ThrowingSupplier)} — snapshot контекста снимается
 *       в момент вызова {@code wrap(...)} (caller-thread) и делается текущим на время выполнения
 *       задачи в worker-thread; внутри этого scope устанавливается и восстанавливается {@link IdentityMdcProjection}.</li>
 *   <li>{@code contextAware(Executor/ExecutorService)} — каждая задача оборачивается на
 *       {@code execute / submit}, поэтому контекст снимается у фактического caller'а, что
 *       корректно для long-lived пулов.</li>
 * </ul>
 * Встроенный {@code Context.taskWrapping(...)} не используется: он переносит контекст, но не MDC.
 */
public class OtelPlatformContextPropagation implements PlatformContextPropagation {

    @Override
    @Nonnull
    public Runnable wrap(@Nonnull Runnable task) {
        Objects.requireNonNull(task, "task");
        Context captured = Context.current();
        return () -> runWithCapturedContext(captured, task);
    }

    @Override
    @Nonnull
    public <T> ThrowingSupplier<T> wrap(@Nonnull ThrowingSupplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");

        Context captured = Context.current();
        return () -> {
            try (Scope ignored = captured.makeCurrent()) {
                IdentityMdcProjection.Snapshot previous = IdentityMdcProjection.installCurrent();
                try {
                    return supplier.get();
                } finally {
                    previous.restore();
                }
            }
        };
    }

    @Override
    @Nonnull
    public Executor contextAware(@Nonnull Executor delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return command -> delegate.execute(wrap(command));
    }

    @Override
    @Nonnull
    public ExecutorService contextAware(@Nonnull ExecutorService delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new MdcProjectingExecutorService(delegate, this);
    }

    private static void runWithCapturedContext(Context captured, Runnable task) {
        try (Scope ignored = captured.makeCurrent()) {
            IdentityMdcProjection.Snapshot previous = IdentityMdcProjection.installCurrent();
            try {
                task.run();
            } finally {
                previous.restore();
            }
        }
    }
}
