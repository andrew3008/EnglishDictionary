package space.br1440.platform.tracing.api.propagation;

/**
 * Зарезервированные платформой ключи W3C Baggage.
 */
public final class PlatformBaggageKeys {

    /** Бизнес-идентификатор корреляции, установленный доверенной границей приложения. */
    public static final String CORRELATION_ID = "platform.correlation.id";

    private PlatformBaggageKeys() {
    }
}
