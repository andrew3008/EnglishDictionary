package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import java.util.Locale;

import space.br1440.platform.common.util.StringUtils;

/**
 * Запрошенный или действующий уровень валидации span-атрибутов для {@code ValidatingSpanProcessor}.
 * <p>
 * Конфигурационное свойство —
 * {@code platform.tracing.javaagent.span-attributes.validation.level}. Агент читает его при
 * инициализации из JVM system property или
 * {@code PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL}, до загрузки Spring.
 * Значения — взаимоисключающие режимы окружения, а не порядковая шкала строгости.
 * <p>
 * Допустимые значения (без учёта регистра):
 * <ul>
 *   <li>{@code WARN} — валидировать и писать throttled WARN-диагностику; span не прерывается</li>
 *   <li>{@code ERROR} — валидировать и писать throttled ERROR-диагностику без прерывания
 *       завершения span. Это диагностический уровень, а не fail-fast: исключения не бросаются
 *       и публичный {@code TraceOperations} не меняется. Отображается в булево
 *       {@code validationStrict=true} без автоматического включения {@code validationEnabled}</li>
 *   <li>{@code DISABLED} — пропустить validation-проверку процессора. Экспорт, scrubbing,
 *       enrichment и остальной composite pipeline продолжают работать. Начальное состояние —
 *       {@code validationEnabled=false} и {@code validationStrict=false}; runtime-включение
 *       validation остаётся отдельной операцией</li>
 * </ul>
 * Отсутствующее или пустое значение сводится к {@link #WARN}. Заданное неизвестное значение,
 * включая снятые {@code LENIENT} и {@code STRICT}, приводит к отказу конфигурации.
 */
public enum SpanAttributeValidationLevel {

    WARN,
    ERROR,
    DISABLED;

    public boolean isError() {
        return (this == ERROR);
    }

    public boolean isDisabled() {
        return (this == DISABLED);
    }

    public static SpanAttributeValidationLevel parse(String value) {
        if (StringUtils.isNullOrBlank(value)) {
            return WARN;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WARN" -> WARN;
            case "ERROR" -> ERROR;
            case "DISABLED" -> DISABLED;
            default -> throw new IllegalStateException("""
                    Invalid property 'platform.tracing.javaagent.span-attributes.validation.level'='%s'. \
                    Accepted values: WARN, ERROR, DISABLED\
                    """.formatted(value)
            );
        };
    }
}
