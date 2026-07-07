package space.br1440.platform.tracing.api.propagation;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.util.ThrowingSupplier;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Платформенный фасад переноса контекста трассировки между потоками.
 * <p>
 * Назначение — дать прикладному коду явный, безопасный и stable API для переноса текущего
 * OpenTelemetry {@code Context} (включая активный span и baggage) в задачи, исполняемые
 * на другом потоке: {@link java.util.concurrent.CompletableFuture}, ручной {@link Executor},
 * собственный {@link ExecutorService}. Без обёртки задачи на worker-потоке {@code Span.current()}
 * вернёт invalid span, и parent-child связь будет потеряна.
 * <p>
 * <b>Когда использовать.</b>
 * <ul>
 *   <li>Custom executor / thread pool, не интегрированный с OTel Java Agent.</li>
 *   <li>{@code CompletableFuture.supplyAsync / runAsync} без явного executor.</li>
 *   <li>Любой dispatch задачи в worker-поток вне agent-instrumented кода.</li>
 * </ul>
 * <p>
 * <b>Когда НЕ использовать.</b>
 * <ul>
 *   <li>HTTP / gRPC / Kafka — пропагация выполняется OTel Java Agent автоматически
 *       через bytecode instrumentation, ручная обёртка приведёт к двойному переносу.</li>
 *   <li>Reactor {@code Mono} / {@code Flux} — используется механизм Reactor hooks и
 *       Micrometer Context Propagation.</li>
 *   <li>Spring {@code @Async} с {@code ThreadPoolTaskExecutor} — включите
 *       {@code platform.tracing.context-propagation.async.enabled=true}, после чего
 *       платформенный {@code TaskDecorator} возьмёт пропагацию на себя.</li>
 * </ul>
 * <p>
 * <b>Контракт реализации.</b> Возвращаемые обёртки должны перехватывать текущий контекст в момент
 * вызова {@code wrap(...)} / {@code contextAware(...)} (caller-thread) и восстанавливать его
 * в worker-thread на время выполнения задачи. После выполнения контекст worker-потока
 * восстанавливается до прежнего состояния. Реализация не должна изменять семантику задачи
 * (исключения, return value, cancellation).
 */
public interface PlatformContextPropagation {

    /**
     * Оборачивает {@link Runnable} так, чтобы он выполнялся с current context, зафиксированным
     * на момент вызова {@code wrap(task)}. Идемпотентность не гарантируется — повторная обёртка
     * технически возможна, но приведёт к двойному capture/restore. Не оборачивайте уже обёрнутые
     * задачи без явной необходимости.
     */
    @Nonnull
    Runnable wrap(@Nonnull Runnable task);

    /**
     * Оборачивает {@link ThrowingSupplier} с переносом current context. Возвращает значение
     * и пробрасывает checked-исключения без изменений. Lambda-совместимый overload, покрывает
     * как unchecked-, так и checked-сценарии. Для интеграции с готовым {@code Callable}
     * используйте method reference: {@code propagation.wrap(callable::call)}.
     */
    @Nonnull
    <T> ThrowingSupplier<T> wrap(@Nonnull ThrowingSupplier<T> supplier);

    /**
     * Возвращает {@link Executor}, который перед запуском каждой задачи восстанавливает
     * context, зафиксированный в момент {@code execute(...)} (а не в момент создания обёртки).
     * Безопасно использовать для long-lived executor'ов с разными caller'ами.
     */
    @Nonnull
    Executor contextAware(@Nonnull Executor delegate);

    /**
     * Возвращает {@link ExecutorService}, переносящий current context в каждую отправленную задачу.
     * {@code shutdown / shutdownNow / awaitTermination} делегируются исходному executor'у.
     */
    @Nonnull
    ExecutorService contextAware(@Nonnull ExecutorService delegate);

}
