package space.br1440.platform.tracing.api.span;

/**
 * Финальный статус span'а в терминах платформенного стандарта.
 * <p>
 * Значение публикуется в атрибут {@code platform.result} и используется на стороне OpenTelemetry Collector'а
 * при принятии решений о tail-sampling и маршрутизации в storage tier с учётом политики хранения.
 */
public enum SpanResult {

    /** Операция завершилась штатно. */
    SUCCESS("success"),

    /** Операция завершилась с ошибкой. */
    FAILURE("failure");

    private final String value;

    SpanResult(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
