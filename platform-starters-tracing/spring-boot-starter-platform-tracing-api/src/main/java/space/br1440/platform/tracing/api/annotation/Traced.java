package space.br1440.platform.tracing.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Декларативная аннотация для оборачивания вызова метода в span платформенной трассировки.
 * <p>
 * Обработка выполняется AOP-аспектом модуля {@code platform-tracing-spring-boot-autoconfigure}
 * при наличии Spring AOP / AspectJ на classpath приложения. Платформенные starters AOP не тянут.
 * <p>
 * <b>Create / enrich.</b> Если активного valid span нет, создаётся INTERNAL-операционный span через
 * {@code spans().operation(name)}. Если активный span есть, child не создаётся: на текущий span
 * пишутся {@code platform.traced.method} и атрибуты параметров с {@link TracedAttribute}.
 * <p>
 * <b>Ограничения.</b> Эффект только на публичных не-{@code final} методах Spring-бинов при внешнем
 * вызове (self-invocation — silent no-op). Не применяется к методам, возвращающим
 * {@code Future}/{@code CompletionStage}/{@code Mono}/{@code Flux}, и к {@code @Async}-методам.
 * Composed / meta-{@code @Traced} не поддерживаются.
 * <p>
 * Если пакет уже использует Micrometer {@code @Observed}, не комбинируйте обе аннотации на одном
 * методе или на class-level {@code @Traced} с method-level {@code @Observed}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Traced {

    /**
     * Имя span'а. Если не задано, используется имя метода.
     */
    String value() default "";

}
