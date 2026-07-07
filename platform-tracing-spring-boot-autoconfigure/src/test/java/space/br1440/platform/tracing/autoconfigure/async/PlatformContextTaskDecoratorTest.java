package space.br1440.platform.tracing.autoconfigure.async;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link PlatformContextTaskDecorator}: проверка переноса MDC, корректной семантики
 * композиции с existing {@link TaskDecorator} (decorate-order и runtime-order),
 * сохранение caller-snapshot до запуска delegate.
 * <p>
 * Тесты используют локальный {@link ContextRegistry} с зарегистрированным
 * {@code Slf4jThreadLocalAccessor}, чтобы не зависеть от глобальной micrometer-tracing
 * конфигурации, и собственный {@link ContextSnapshotFactory} на базе этого registry.
 */
class PlatformContextTaskDecoratorTest {

    private ContextSnapshotFactory snapshotFactory;
    private ExecutorService worker;

    @BeforeEach
    void setUp() {
        ContextRegistry registry = new ContextRegistry()
                .registerThreadLocalAccessor(new io.micrometer.context.integration.Slf4jThreadLocalAccessor());
        snapshotFactory = ContextSnapshotFactory.builder().contextRegistry(registry).build();
        worker = Executors.newSingleThreadExecutor();
    }

    @Test
    void decorate_без_delegate_переносит_mdc_в_worker_thread() throws Exception {
        PlatformContextTaskDecorator decorator = new PlatformContextTaskDecorator(snapshotFactory);
        AtomicReference<String> captured = new AtomicReference<>();

        MDC.put("traceId", "abcdef0123456789abcdef0123456789");
        try {
            Runnable decorated = decorator.decorate(() -> captured.set(MDC.get("traceId")));
            worker.submit(decorated).get(5, TimeUnit.SECONDS);
        } finally {
            MDC.remove("traceId");
        }

        assertThat(captured.get()).isEqualTo("abcdef0123456789abcdef0123456789");
    }

    @Test
    void decorate_с_delegate_выполняет_decorate_delegate_до_платформенной_обёртки() throws Exception {
        // Этот тест валидирует ключевой архитектурный инвариант плана:
        // platformOuter(existing.decorate(task)) — existing.decorate вызывается СНАРУЖИ
        // в момент decorate() (caller-side), а ContextSnapshot.wrap охватывает уже decorated-task.
        List<String> decorateCalls = new ArrayList<>();
        TaskDecorator existing = runnable -> {
            decorateCalls.add("existing-decorate");
            return runnable;
        };

        PlatformContextTaskDecorator decorator = new PlatformContextTaskDecorator(snapshotFactory, existing);
        decorator.decorate(() -> {});

        // existing.decorate вызывается ровно один раз во время composition.
        assertThat(decorateCalls).containsExactly("existing-decorate");
        assertThat(decorator.getDelegate()).isSameAs(existing);
    }

    @Test
    void decorate_с_delegate_выполняет_existing_runtime_logic_внутри_платформенного_контекста()
            throws Exception {
        // Существующий decorator выставляет MDC в worker-runtime "tenant". Платформенный
        // снимок берётся в caller-thread с пустым MDC. Если порядок неправильный (existing
        // снаружи), tenant перетрёт платформенный traceId. При корректном порядке —
        // tenant логика выполняется ПОСЛЕ восстановления caller-snapshot, и в момент
        // запуска task видны и tenant, и traceId одновременно.
        TaskDecorator tenantDecorator = runnable -> () -> {
            MDC.put("tenant", "acme");
            try {
                runnable.run();
            } finally {
                MDC.remove("tenant");
            }
        };
        PlatformContextTaskDecorator decorator = new PlatformContextTaskDecorator(snapshotFactory, tenantDecorator);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedTenant = new AtomicReference<>();

        MDC.put("traceId", "abcdef0123456789abcdef0123456789");
        try {
            Runnable decorated = decorator.decorate(() -> {
                capturedTrace.set(MDC.get("traceId"));
                capturedTenant.set(MDC.get("tenant"));
                latch.countDown();
            });
            worker.submit(decorated);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            MDC.remove("traceId");
        }

        assertThat(capturedTrace.get()).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(capturedTenant.get()).isEqualTo("acme");
    }

    @Test
    void getDelegate_возвращает_null_когда_delegate_не_передан() {
        PlatformContextTaskDecorator decorator = new PlatformContextTaskDecorator(snapshotFactory);
        assertThat(decorator.getDelegate()).isNull();
    }
}
