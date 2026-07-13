package space.br1440.platform.tracing.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает параметр метода, значение которого должно быть автоматически добавлено как атрибут текущего span'а.
 * <p>
 * Действует совместно с аннотацией {@link Traced} на методе или на классе.
 * Атрибуты, помеченные {@code @TracedAttribute}, попадают на span как
 * {@code <key> = String.valueOf(parameter)}.
 * <p>
 * Чувствительные данные не должны помечаться этой аннотацией. Подробности — в платформенных правилах
 * скраббинга атрибутов и в SPI {@code SpanAttributeScrubbingRule}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TracedAttribute {

    /**
     * Имя ключа атрибута. Если не задано, используется имя параметра (требует компиляции с {@code -parameters}).
     */
    String value() default "";
}
