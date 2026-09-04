package space.br1440.platform.leaderelection.annotation.locking;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для блокировки выполнения методов на основе лидерства.
 * Метод будет выполнен только, если текущий экземпляр является лидером для указанной роли.
 * Поддерживает динамическое задание параметров через SpEL-выражения и внешние параметры.
 * <p>
 * Аннотация @SchedulerLock наиболее эффективно используется совместно с @Scheduled,
 * так как механизм блокировки интегрирован со Spring через ScheduledTaskObservationContext.
 * Такая интеграция обеспечивает оптимальную производительность и корректную работу с Spring Scheduling Framework.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SchedulerLock {

    /**
     * Имя блокировки (lock name), уникальный идентификатор для распределённой блокировки.
     * Разные методы с одинаковым lockName конкурируют за одну и ту же блокировку.
     */
    String lockName();

    /**
     * Максимальное время удержания блокировки.
     * Если выполнение метода превысит это время, блокировка будет автоматически освобождена.
     * Формат: ISO-8601 Duration (например, "30m" для 30 минут).
     * Поддерживает SpEL-выражения и внешние параметры (например, "${lock.max:30m}" или "#{@code @configService.getLockTimeout()}").
     * Если не указано, используется значение по умолчанию из настройки {@code space.br1440.platform.leaderelection.configuration.LeaderElectionProperties.LockSettings#getDefaultLockAtMostFor()}.
     */
    String lockAtMostFor() default "";

    /**
     * Минимальное время удержания блокировки.
     * Блокировка будет удерживаться как минимум указанное время, даже если метод завершился раньше.
     * Формат: ISO-8601 Duration (например, "1m" для 1 минуты).
     * Поддерживает SpEL-выражения и внешние параметры (например, "${lock.min:1m}" или "#{@code @configService.getMinLockTime()}").
     * Если не указано, используется значение по умолчанию из настройки {@code space.br1440.platform.leaderelection.configuration.LeaderElectionProperties.LockSettings#getDefaultLockAtLeastFor()}.
     */
    String lockAtLeastFor() default "";

    /**
     * Время удержания блокировки после завершения выполнения метода.
     * Используется для предотвращения частых переключений лидерства.
     * Формат: ISO-8601 Duration (например, "5m" для 5 минут).
     * Поддерживает SpEL-выражения и внешние параметры.
     * Если не указано, блокировка освобождается сразу после завершения метода.
     */
    String keepAliveLockUntil() default "";

}
