package space.br1440.platform.tracing.api.annotation;

import space.br1440.platform.tracing.api.span.SpanCategory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Декларативная аннотация для оборачивания вызова метода в span платформенной трассировки.
 * <p>
 * Обработка аннотации выполняется AOP-аспектом, поставляемым модулем
 * {@code platform-tracing-spring-boot-autoconfigure}. Поведение совместимо с Micrometer Observation
 * (аналог {@code io.micrometer.observation.annotation.Observed}), но всегда оперирует категориями
 * платформенного стандарта.
 * <p>
 * Если пакет приложения уже использует {@code @Observed}, имеет смысл придерживаться одной аннотации
 * во всём сервисе. {@code @Traced} рекомендуется как платформенный «канонический» вариант.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Traced {

    /**
     * Имя span'а. Если не задано, используется простое имя метода.
     */
    String value() default "";

    /**
     * Категория span'а в терминах платформенного стандарта. Значение попадает в атрибут {@code platform.type}.
     */
    SpanCategory category() default SpanCategory.INTERNAL;

    /**
     * Имена параметров метода, чьи значения должны быть автоматически добавлены как атрибуты span'а.
     * Применяется к параметрам, помеченным {@link TracedAttribute}.
     */
    String[] attributes() default {};

}
