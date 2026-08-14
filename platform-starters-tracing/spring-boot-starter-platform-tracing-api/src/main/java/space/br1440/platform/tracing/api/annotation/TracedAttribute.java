package space.br1440.platform.tracing.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает параметр метода, значение которого должно быть автоматически добавлено как атрибут текущего span'а.
 * <p>
 * Действует совместно с {@link Traced} на методе или на классе. Значение пишется как
 * {@code <key> = String.valueOf(parameter)}. Допустимые типы параметра: примитивы, их обёртки,
 * {@link String} и конкретные enum-типы (см. ArchUnit {@code TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST}).
 * Meta-аннотации на параметре не поддерживаются.
 * <p>
 * Аннотация не является средством защиты от PII: чувствительные значения помечать нельзя.
 * Скраббинг и ограничение длины значения применяются обязательным agent-pipeline'ом
 * ({@code ScrubbingSpanProcessor}, {@code otel.span.attribute.value.length.limit}): платформенный
 * runtime не стартует без READY Controlled Platform Agent. SPI {@code SpanAttributeScrubbingRule}
 * объявляет контракт правил; движок и встроенные правила живут в agent-extension.
 * <p>
 * Пустой {@link #value()} использует имя параметра и требует компиляции с {@code -parameters}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TracedAttribute {

    /**
     * Имя ключа атрибута. Если не задано, используется имя параметра (требует компиляции с {@code -parameters}).
     */
    String value() default "";

}
