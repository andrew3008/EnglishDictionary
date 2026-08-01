package space.br1440.platform.tracing.autoconfigure.integrations.async;

import io.micrometer.context.ContextSnapshotFactory;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

/**
 * {@link BeanPostProcessor}, устанавливающий платформенный {@link PlatformContextTaskDecorator}
 * на все {@link ThreadPoolTaskExecutor}-бины контекста.
 * <p>
 * <b>Activation guard.</b> Регистрация BPP управляется property
 * {@code platform.tracing.thread-pool-task-executor.enabled=true} на уровне
 * {@code TracingAsyncContextAutoConfiguration}. Сам BPP не делает условную проверку — это
 * ответственность auto-configuration.
 *
 * @see PlatformContextTaskDecorator
 */
@Slf4j
@RequiredArgsConstructor
public class ThreadPoolTaskExecutorDecoratorBeanPostProcessor implements BeanPostProcessor, Ordered {

    /**
     * Имя приватного поля {@code taskDecorator} в {@link ThreadPoolTaskExecutor} (стабильно
     * между Spring 5.x / 6.x). Чтение через reflection — единственный способ достать существующий
     * decorator, поскольку Spring не выставляет getter. При отсутствии поля BPP gracefully
     * пропускает composition и устанавливает платформенный decorator как единственный.
     */
    private static final String TASK_DECORATOR_FIELD = "taskDecorator";

    private final ContextSnapshotFactory snapshotFactory;

    @Override
    public Object postProcessBeforeInitialization(@Nonnull Object bean, @Nonnull String beanName) throws BeansException {
        if (!(bean instanceof ThreadPoolTaskExecutor executor)) {
            return bean;
        }

        TaskDecorator existing = readExistingTaskDecorator(executor);
        if (existing instanceof PlatformContextTaskDecorator) {
            log.debug("ThreadPoolTaskExecutor '{}' already has PlatformContextTaskDecorator — skipping", beanName);
            return bean;
        }

        PlatformContextTaskDecorator composed = new PlatformContextTaskDecorator(snapshotFactory, existing);
        executor.setTaskDecorator(composed);

        if (existing != null) {
            log.debug("ThreadPoolTaskExecutor '{}' received composed PlatformContextTaskDecorator "
                    + "on top of existing {}", beanName, existing.getClass().getName());
        } else {
            log.debug("ThreadPoolTaskExecutor '{}' received PlatformContextTaskDecorator (no delegate)", beanName);
        }

        return bean;
    }

    /**
     * Читает существующий {@link TaskDecorator} через reflection. Spring не предоставляет
     * публичного getter'а — это вынужденная мера для реализации composition без replacement.
     */
    @Nullable
    private TaskDecorator readExistingTaskDecorator(ThreadPoolTaskExecutor executor) {
        Field field = ReflectionUtils.findField(ThreadPoolTaskExecutor.class, TASK_DECORATOR_FIELD);
        if (field == null) {
            log.warn("Field '{}' not found on ThreadPoolTaskExecutor — composition with existing TaskDecorator "
                    + "is unavailable; the platform decorator will be set as the only one. "
                    + "Spring internal API may have changed.", TASK_DECORATOR_FIELD);
            return null;
        }

        try {
            ReflectionUtils.makeAccessible(field);
            Object value = field.get(executor);
            return (value instanceof TaskDecorator td) ? td : null;
        } catch (IllegalAccessException | RuntimeException e) {
            log.warn("Failed to read existing TaskDecorator via reflection: {}; "
                    + "composition degrades to installing the platform decorator without a delegate", e.getMessage());
            return null;
        }
    }

    @Override
    public int getOrder() {
        // LOWEST_PRECEDENCE — платформенный BPP запускается последним, чтобы любые предыдущие
        // BPP (Spring Security, Micrometer executor metrics, SB 3.5 ContextPropagatingTaskDecorator
        // configurer) успели установить свои decorator'ы. На момент композиции existing decorator
        // уже представляет финальную цепочку прочих infrastructure beans.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
