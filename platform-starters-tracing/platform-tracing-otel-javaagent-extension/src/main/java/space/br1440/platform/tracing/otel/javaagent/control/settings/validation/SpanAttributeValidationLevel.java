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
 *   <li>{@code WARNING} — валидировать и писать throttled WARN-диагностику; span не прерывается</li>
 *   <li>{@code ERROR} — валидировать и писать throttled ERROR-диагностику без прерывания
 *       завершения span. Это диагностический уровень, а не fail-fast: исключения не бросаются
 *       и публичный {@code TraceOperations} не меняется</li>
 *   <li>{@code DISABLED} — пропустить validation-проверку процессора. Экспорт, scrubbing,
 *       enrichment и остальной composite pipeline продолжают работать. Повторное включение
 *       требует явного {@code WARNING} или {@code ERROR}</li>
 * </ul>
 * Startup: отсутствующее или пустое значение сводится к {@link #WARNING}. Runtime apply и
 * полный snapshot: отсутствующее, пустое или неизвестное значение отклоняет обновление.
 * Заданное неизвестное значение, включая снятые {@code WARN}, {@code LENIENT} и {@code STRICT},
 * приводит к отказу.
 */
public enum SpanAttributeValidationLevel {

    WARNING,
    ERROR,
    DISABLED;

    public boolean isError() {
        return (this == ERROR);
    }

    public boolean isDisabled() {
        return (this == DISABLED);
    }

    /**
     * Startup-разбор: пустое значение даёт {@link #WARNING}.
     */
    public static SpanAttributeValidationLevel parse(String value) {
        if (StringUtils.isNullOrBlank(value)) {
            return WARNING;
        }

        return parseKnownToken(value, true);
    }

    /**
     * Runtime-разбор: пустое или неизвестное значение отклоняет обновление.
     */
    public static SpanAttributeValidationLevel require(String value) {
        if (StringUtils.isNullOrBlank(value)) {
            throw invalidRuntimeToken(value);
        }

        return parseKnownToken(value, false);
    }

    private static SpanAttributeValidationLevel parseKnownToken(String value, boolean startup) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WARNING" -> WARNING;
            case "ERROR" -> ERROR;
            case "DISABLED" -> DISABLED;
            default -> {
                if (startup) {
                    throw new IllegalStateException(invalidTokenMessage(value));
                }
                throw invalidRuntimeToken(value);
            }
        };
    }

    private static IllegalArgumentException invalidRuntimeToken(String value) {
        return new IllegalArgumentException(invalidTokenMessage(value));
    }

    private static String invalidTokenMessage(String value) {
        return """
                Invalid property 'platform.tracing.javaagent.span-attributes.validation.level'='%s'. \
                Accepted values: WARNING, ERROR, DISABLED\
                """.formatted(value);
    }
}
