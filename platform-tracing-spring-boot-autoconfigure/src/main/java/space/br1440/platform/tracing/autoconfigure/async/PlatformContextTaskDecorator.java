package space.br1440.platform.tracing.autoconfigure.async;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.Nullable;
import org.springframework.core.task.TaskDecorator;

/**
 * Платформенный {@link TaskDecorator}, переносящий OpenTelemetry {@code Context} и MDC
 * между caller-потоком и worker-потоком {@code ThreadPoolTaskExecutor}.
 * <p>
 * Реализован поверх Micrometer {@code ContextSnapshotFactory.captureAll()}, что обеспечивает
 * перенос всех зарегистрированных {@code ThreadLocalAccessor}'ов (OTel Context, SLF4J MDC,
 * Spring Security {@code SecurityContext} — при наличии соответствующих accessor'ов в classpath)
 * единой операцией без явного перечисления каждого носителя.
 * <p>
 * <b>Composition вместо replacement.</b> Если декоратор создаётся с не-null delegate, обёртка
 * формируется как {@code platformOuter(existing.decorate(task))} — снимок контекста
 * захватывается в caller-thread <em>до</em> запуска existing decorator, а в worker-thread
 * восстановление OTel / MDC происходит <em>до</em> выполнения логики existing decorator
 * (security / tenant / transaction-aware wrappers). Это критично для существующих decorator'ов,
 * которые сами читают OTel/MDC при decorate.
 * <p>
 * <b>Mode.</b> На v0.1.0 поддерживается единственный режим {@code propagate-current-context}:
 * span автоматически не создаётся, переносится только контекст. Создание span'а — задача
 * прикладного кода через {@code @Traced} / {@code PlatformTracing.inSpan}.
 *
 * @see io.micrometer.context.ContextSnapshotFactory
 */
public class PlatformContextTaskDecorator implements TaskDecorator {

    private final ContextSnapshotFactory snapshotFactory;

    @Nullable
    private final TaskDecorator delegate;

    /**
     * Создаёт декоратор без существующего delegate. Используется, когда executor не имеет
     * предустановленного {@code TaskDecorator}.
     */
    public PlatformContextTaskDecorator(ContextSnapshotFactory snapshotFactory) {
        this(snapshotFactory, null);
    }

    /**
     * Создаёт декоратор поверх существующего {@link TaskDecorator}. Платформенный декоратор
     * остаётся самым внешним слоем: capture контекста выполняется в caller-thread до
     * delegate.decorate(...), а restore в worker-thread выполняется до запуска уже
     * декорированной задачи.
     */
    public PlatformContextTaskDecorator(ContextSnapshotFactory snapshotFactory,
                                        @Nullable TaskDecorator delegate) {
        this.snapshotFactory = snapshotFactory;
        this.delegate = delegate;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        // Сначала прогоняем задачу через existing decorator, чтобы получить уже security/MDC/tenant-aware
        // Runnable. Затем оборачиваем результат платформенным contextSnapshot.wrap, который снимает
        // OTel/MDC снапшот в caller-thread (decorate вызывается caller'ом при submit) и восстанавливает
        // его в worker-thread до выполнения decorated-Runnable.
        Runnable decorated = delegate != null ? delegate.decorate(runnable) : runnable;
        ContextSnapshot snapshot = snapshotFactory.captureAll();
        return snapshot.wrap(decorated);
    }

    /**
     * Возвращает delegate, обёрнутый этим декоратором. {@code null}, если decorator работает
     * без существующего delegate. Метод полезен для тестов и диагностики.
     */
    @Nullable
    public TaskDecorator getDelegate() {
        return delegate;
    }
}
