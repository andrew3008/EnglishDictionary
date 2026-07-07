package space.br1440.platform.tracing.core;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.PlatformContextPropagation;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link NoOpPlatformContextPropagation}: все методы возвращают исходный объект без обёртки.
 * Используется в degraded-окружении без подключённого OpenTelemetry SDK.
 */
class NoOpPlatformContextPropagationTest {

    private final PlatformContextPropagation propagation = NoOpPlatformContextPropagation.INSTANCE;

    @Test
    void wrapRunnable_возвращает_исходный_объект() {
        Runnable task = () -> {};
        assertThat(propagation.wrap(task)).isSameAs(task);
    }

    @Test
    void wrapThrowingSupplier_возвращает_исходный_объект() {
        ThrowingSupplier<String> task = () -> "v";
        assertThat(propagation.wrap(task)).isSameAs(task);
    }

    @Test
    void contextAwareExecutor_возвращает_исходный_объект() {
        Executor delegate = Runnable::run;
        assertThat(propagation.contextAware(delegate)).isSameAs(delegate);
    }

    @Test
    void contextAwareExecutorService_возвращает_исходный_объект() {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        try {
            assertThat(propagation.contextAware(delegate)).isSameAs(delegate);
        } finally {
            delegate.shutdownNow();
        }
    }
}
