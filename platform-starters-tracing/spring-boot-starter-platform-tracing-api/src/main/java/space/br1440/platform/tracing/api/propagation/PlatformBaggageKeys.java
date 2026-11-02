package space.br1440.platform.tracing.api.propagation;

import lombok.experimental.UtilityClass;

/**
 * Зарезервированные платформой ключи W3C Baggage.
 */
@UtilityClass
public final class PlatformBaggageKeys {

    /** Бизнес-идентификатор корреляции, установленный доверенной границей приложения. */
    public static final String CORRELATION_ID = "platform.correlation.id";

}
