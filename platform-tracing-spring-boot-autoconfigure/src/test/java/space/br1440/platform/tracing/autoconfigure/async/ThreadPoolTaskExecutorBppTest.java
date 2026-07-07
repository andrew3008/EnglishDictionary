package space.br1440.platform.tracing.autoconfigure.async;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link ThreadPoolTaskExecutorContextPropagationBeanPostProcessor}: проверка
 * установки декоратора, корректной композиции с существующим {@link TaskDecorator},
 * защиты от двойной обёртки и значения {@link Ordered#getOrder()}.
 */
class ThreadPoolTaskExecutorBppTest {

    private ContextSnapshotFactory snapshotFactory;
    private ThreadPoolTaskExecutorContextPropagationBeanPostProcessor bpp;

    @BeforeEach
    void setUp() {
        snapshotFactory = ContextSnapshotFactory.builder().build();
        bpp = new ThreadPoolTaskExecutorContextPropagationBeanPostProcessor(snapshotFactory);
    }

    @Test
    void устанавливает_платформенный_декоратор_на_executor_без_existing() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        Object processed = bpp.postProcessBeforeInitialization(executor, "asyncExecutor");

        assertThat(processed).isSameAs(executor);
        TaskDecorator decorator = readTaskDecorator(executor);
        assertThat(decorator).isInstanceOf(PlatformContextTaskDecorator.class);
        assertThat(((PlatformContextTaskDecorator) decorator).getDelegate()).isNull();
    }

    @Test
    void композирует_existing_decorator_поверх_executor() {
        // Имитируем сценарий Spring Boot 3.5 ContextPropagatingTaskDecorator или
        // Spring Security executor decorator: existing уже установлен до платформенного BPP.
        TaskDecorator existing = runnable -> runnable;
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(existing);

        bpp.postProcessBeforeInitialization(executor, "asyncExecutor");

        TaskDecorator current = readTaskDecorator(executor);
        assertThat(current).isInstanceOf(PlatformContextTaskDecorator.class);
        assertThat(((PlatformContextTaskDecorator) current).getDelegate()).isSameAs(existing);
    }

    @Test
    void повторная_обёртка_не_создаёт_двойной_decorator() {
        // Сценарий context refresh: BPP вызывается дважды на одном executor'е.
        // Платформенный декоратор должен остаться единственным внешним слоем.
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        bpp.postProcessBeforeInitialization(executor, "asyncExecutor");
        TaskDecorator afterFirst = readTaskDecorator(executor);

        bpp.postProcessBeforeInitialization(executor, "asyncExecutor");
        TaskDecorator afterSecond = readTaskDecorator(executor);

        assertThat(afterSecond).isSameAs(afterFirst);
        // delegate первой обёртки был null; если бы повторный вызов скомпоновал заново,
        // delegate стал бы PlatformContextTaskDecorator — это явный signal двойной обёртки.
        assertThat(((PlatformContextTaskDecorator) afterSecond).getDelegate()).isNull();
    }

    /**
     * Читает {@code taskDecorator} через reflection — Spring не выставляет публичного getter'а.
     * Дублирует приватную логику BPP, чтобы тесты не зависели от внутреннего API BPP.
     */
    private static TaskDecorator readTaskDecorator(ThreadPoolTaskExecutor executor) {
        Field field = ReflectionUtils.findField(ThreadPoolTaskExecutor.class, "taskDecorator");
        if (field == null) {
            throw new AssertionError("Поле 'taskDecorator' не найдено в ThreadPoolTaskExecutor");
        }
        ReflectionUtils.makeAccessible(field);
        Object value = ReflectionUtils.getField(field, executor);
        return value instanceof TaskDecorator td ? td : null;
    }

    @Test
    void не_трогает_бины_которые_не_являются_ThreadPoolTaskExecutor() {
        Object foreign = new Object();
        Object processed = bpp.postProcessBeforeInitialization(foreign, "irrelevantBean");
        assertThat(processed).isSameAs(foreign);
    }

    @Test
    void имеет_приоритет_LOWEST_PRECEDENCE() {
        // Критично для multi-BPP сценариев: платформенный BPP запускается после всех
        // остальных BeanPostProcessor'ов (Security, Micrometer executor metrics,
        // Spring Boot 3.5 ContextPropagatingTaskDecorator configurer), что гарантирует
        // финализацию existing TaskDecorator-цепочки на момент композиции.
        assertThat(bpp.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
