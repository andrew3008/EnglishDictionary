package space.br1440.platform.errorhandling.utils;

import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * Конвертация имени Java exception класса в UPPER_SNAKE_CASE reason code.
 *
 * <p>Используется в {@link space.br1440.platform.errorhandling.exception.AbstractErrorResponseException}
 * для автоматического вывода {@code errorCode} из runtime-типа исключения (Convention over Configuration).
 *
 * <h3>Алгоритм</h3>
 * <ol>
 *   <li>Берётся простое имя класса ({@code getSimpleName()})</li>
 *   <li>Убирается суффикс {@code Exception} (если длина позволяет)</li>
 *   <li>CamelCase конвертируется в UPPER_SNAKE_CASE</li>
 * </ol>
 *
 * <h3>Примеры</h3>
 * <pre>
 * ResourceNotFoundException       -> RESOURCE_NOT_FOUND
 * DuplicateKeyException           -> DUPLICATE_KEY
 * ExternalServiceUnavailableException -> EXTERNAL_SERVICE_UNAVAILABLE
 * InternalServerException         -> INTERNAL_SERVER
 * CircuitBreakerOpenException     -> CIRCUIT_BREAKER_OPEN
 * </pre>
 */
@UtilityClass
public final class ReasonCodeUtils {

    private static final String EXCEPTION_SUFFIX = "Exception";
    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    public static String toReasonCode(Class<?> exceptionClass) {
        String simple = exceptionClass.getSimpleName();
        if (simple.endsWith(EXCEPTION_SUFFIX) && simple.length() > EXCEPTION_SUFFIX.length()) {
            simple = simple.substring(0, simple.length() - EXCEPTION_SUFFIX.length());
        }

        return CAMEL_SPLIT.matcher(simple).replaceAll("_").toUpperCase();
    }
}
