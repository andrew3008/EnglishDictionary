package space.br1440.platform.tracing.api.span;

/**
 * Финальный статус span'а.
 * <p>
 * Значение публикуется в атрибут {@code platform.trace.result} и используется на стороне
 * OpenTelemetry Collector'а при принятии решений о tail-sampling.
 */
public enum SpanResult {

    /** Операция завершилась штатно. */
    SUCCESS("success"),

    /** Операция завершилась с ошибкой. */
    FAILURE("failure"),

    /** Span принудительно закрыт watchdog'ом по таймауту. */
    TIMEOUT("timeout"),

    /** Операция отменена (например, {@code CancellationException}). */
    CANCELLED("cancelled"),

    /** Запрос отклонён (например, rate limit / circuit breaker). */
    REJECTED("rejected"),

    /** Операция пропущена намеренно (no-op path). */
    SKIPPED("skipped");

    private final String value;

    SpanResult(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
