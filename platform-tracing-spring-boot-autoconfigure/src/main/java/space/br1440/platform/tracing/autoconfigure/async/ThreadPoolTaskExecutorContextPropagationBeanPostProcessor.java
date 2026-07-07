package space.br1440.platform.tracing.autoconfigure.async;

import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.Nullable;
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
 * <b>Composition, не replacement.</b> Если у executor'а уже установлен {@code TaskDecorator}
 * (через Spring Boot 3.5 {@code ContextPropagatingTaskDecorator}, Spring Security, кастомный
 * {@code @Configuration} приложения, иной {@code BeanPostProcessor}), он сохраняется как
 * delegate, а платформенный декоратор оборачивает его снаружи.
 * <p>
 * <b>{@link Ordered#LOWEST_PRECEDENCE}.</b> BPP выполняется последним среди всех
 * BeanPostProcessor'ов executor-беанов, что гарантирует: на момент его работы существующий
 * {@code TaskDecorator} уже финализирован (Security/Micrometer/SB3.5 модифицировали executor).
 * Это критично для корректной композиции — иначе позже зарегистрированный BPP мог бы
 * перетереть платформенную обёртку, и OTel Context потерял бы caller-snapshot до выполнения
 * delegated logic.
 * <p>
 * <b>Activation guard.</b> Регистрация BPP управляется property
 * {@code platform.tracing.context-propagation.async.enabled=true} на уровне
 * {@code TracingAsyncContextAutoConfiguration}. Сам BPP не делает условную проверку — это
 * ответственность auto-configuration.
 *
 * @see PlatformContextTaskDecorator
 */
public class ThreadPoolTaskExecutorContextPropagationBeanPostProcessor
        implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(
            ThreadPoolTaskExecutorContextPropagationBeanPostProcessor.class);

    /**
     * Имя приватного поля {@code taskDecorator} в {@link ThreadPoolTaskExecutor} (стабильно
     * между Spring 5.x / 6.x). Чтение через reflection — единственный способ достать существующий
     * decorator, поскольку Spring не выставляет getter. При отсутствии поля BPP gracefully
     * пропускает composition и устанавливает платформенный decorator как единственный.
     */
    private static final String TASK_DECORATOR_FIELD = "taskDecorator";

    private final ContextSnapshotFactory snapshotFactory;

    public ThreadPoolTaskExecutorContextPropagationBeanPostProcessor(ContextSnapshotFactory snapshotFactory) {
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof ThreadPoolTaskExecutor executor)) {
            return bean;
        }

        TaskDecorator existing = readExistingTaskDecorator(executor);

        // Защита от двойной обёртки при повторной инициализации (например, refresh контекста):
        // если на executor уже установлен платформенный декоратор, оставляем bean без изменений.
        if (existing instanceof PlatformContextTaskDecorator) {
            log.debug("ThreadPoolTaskExecutor '{}' уже имеет PlatformContextTaskDecorator — пропускаем", beanName);
            return bean;
        }

        PlatformContextTaskDecorator composed = new PlatformContextTaskDecorator(snapshotFactory, existing);
        executor.setTaskDecorator(composed);

        if (existing != null) {
            log.debug("ThreadPoolTaskExecutor '{}' получил композированный PlatformContextTaskDecorator "
                    + "поверх существующего {}", beanName, existing.getClass().getName());
        } else {
            log.debug("ThreadPoolTaskExecutor '{}' получил PlatformContextTaskDecorator (без delegate)", beanName);
        }

        return bean;
    }

    /**
     * Читает существующий {@link TaskDecorator} через reflection. Spring не предоставляет
     * публичного getter'а — это вынужденная мера для реализации composition без replacement.
     * <p>
     * При любой ошибке reflection возвращается {@code null} и логируется warning: композиция
     * деградирует до replacement-варианта, но не блокирует инициализацию контекста.
     */
    @Nullable
    private TaskDecorator readExistingTaskDecorator(ThreadPoolTaskExecutor executor) {
        Field field = ReflectionUtils.findField(ThreadPoolTaskExecutor.class, TASK_DECORATOR_FIELD);
        if (field == null) {
            log.warn("Поле '{}' в ThreadPoolTaskExecutor не найдено — composition с existing TaskDecorator "
                    + "недоступна; платформенный decorator будет установлен как единственный. "
                    + "Возможно, изменён внутренний API Spring.", TASK_DECORATOR_FIELD);
            return null;
        }

        try {
            ReflectionUtils.makeAccessible(field);
            Object value = field.get(executor);
            return value instanceof TaskDecorator td ? td : null;
        } catch (IllegalAccessException | RuntimeException e) {
            log.warn("Не удалось прочитать существующий TaskDecorator через reflection: {}; "
                    + "композиция деградирует до установки платформенного decorator без delegate", e.getMessage());
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
