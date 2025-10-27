package space.br1440.platform.leaderelection.annotation.locking;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для методов, которые должны быть выполнены при освобождении блокировки.
 * Используется для побочных действий: публикация событий, очистка ресурсов, финализация контекста, аудит.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnLockReleased {

    /**
     * Имя блокировки, для которой должен выполняться хук-метод при освобождении блокировки
     * аннотацией @SchedulerLock или @DistributedLock.
     */
    String lockName();

    /**
     * Порядок выполнения хук-метода при наличии нескольких хуков для одной роли.
     * Меньшие значения выполняются раньше.
     * Синхронные хуки выполняются перед асинхронными независимо от order.
     */
    int order() default 0;

    /**
     * Определяет, должен ли хук выполняться асинхронно.
     * - false (по умолчанию): хук выполняется синхронно в том же потоке
     * - true: хук выполняется асинхронно в отдельном потоке после освобождения блокировки
     */
    boolean async() default false;

}
