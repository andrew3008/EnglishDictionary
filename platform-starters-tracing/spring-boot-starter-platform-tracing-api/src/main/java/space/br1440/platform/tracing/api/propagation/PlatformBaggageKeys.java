package space.br1440.platform.tracing.api.propagation;

import lombok.experimental.UtilityClass;

/**
 * Зарезервированные платформой ключи W3C Baggage.
 * <p>
 * Префикс {@code platform.} принадлежит платформе целиком. Ключи с этим префиксом
 * пишет только платформа и только внутри процесса.
 */
@UtilityClass
public final class PlatformBaggageKeys {

    /** Префикс ключей, которые платформа не принимает извне и не отдаёт наружу. */
    public static final String RESERVED_PREFIX = "platform.";

    /** Бизнес-идентификатор корреляции, установленный доверенной границей приложения. */
    public static final String CORRELATION_ID = "platform.correlation.id";

    /**
     * Истина для любого ключа с префиксом {@code platform.} без учёта регистра.
     * Сравнение начинается с первого символа: {@code platformx.y} и {@code xplatform.y} не зарезервированы.
     */
    public static boolean isReserved(String key) {
        return (key != null) && key.regionMatches(true, 0, RESERVED_PREFIX, 0, RESERVED_PREFIX.length());
    }
}
