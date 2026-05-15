package space.br1440.platform.errorhandling.core.util.correlation;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.regex.Pattern;

/**
 * Компилирует whitelist-{@link Pattern} для значений correlation-id из настроек.
 *
 * <p>Production-конфигурации почти всегда используют строку
 * {@link ErrorHandlingProperties.CorrelationId#DEFAULT_ALLOWED_PATTERN}; её компиляция
 * в NFA дорогая, поэтому разделяем один статический скомпилированный экземпляр между
 * всеми correlation-резолверами платформы.
 */
@UtilityClass
public final class CorrelationIdAllowedPatternCompilation {

    /**
     * Статический кэш для дефолтного строкового шаблона — см. описание класса.
     */
    private static final Pattern DEFAULT_ALLOWED_PATTERN_COMPILED =
            Pattern.compile(ErrorHandlingProperties.CorrelationId.DEFAULT_ALLOWED_PATTERN);

    /**
     * Скомпилированное выражение по {@link ErrorHandlingProperties.CorrelationId#getAllowedPattern()}.
     * Для строки, совпадающей с дефолтом платформы, возвращает разделяемый {@link Pattern}.
     */
    @NonNull
    public static Pattern compile(@NonNull ErrorHandlingProperties.CorrelationId correlationIdConfig) {
        String configured = correlationIdConfig.getAllowedPattern();
        return ErrorHandlingProperties.CorrelationId.DEFAULT_ALLOWED_PATTERN.equals(configured)
                ? DEFAULT_ALLOWED_PATTERN_COMPILED
                : Pattern.compile(configured);
    }
}
