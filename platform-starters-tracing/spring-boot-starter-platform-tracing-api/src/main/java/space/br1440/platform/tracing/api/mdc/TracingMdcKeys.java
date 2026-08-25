package space.br1440.platform.tracing.api.mdc;

import lombok.experimental.UtilityClass;

/**
 * Канонические имена ключей MDC, используемые при интеграции с {@code spring-boot-starter-platform-logging}
 * и платформенным error-handling.
 * <p>
 * Имена {@code traceId}, {@code spanId}, {@code traceFlags} выровнены с конвенциями
 * {@code micrometer-tracing} и {@code micrometer-tracing-bridge-otel} (см. {@code OtelCurrentTraceContext}),
 * чтобы обеспечить совместимость со стандартным logback-pattern'ом Spring Boot 3.x вида
 * {@code %X{traceId} %X{spanId}} и не требовать кастомного формата на стороне logging-стартера.
 * <p>
 * Идентификаторы запроса и бизнес-корреляции разделены и используют camelCase. MDC является
 * только проекцией внутреннего execution-контекста, а не источником истины.
 */
@UtilityClass
public final class TracingMdcKeys {

    /** Идентификатор trace'а в шестнадцатеричной форме (32 символа). */
    public static final String TRACE_ID = "traceId";

    /** Идентификатор активного span'а в шестнадцатеричной форме (16 символов). */
    public static final String SPAN_ID = "spanId";

    /** Флаги trace context (W3C trace flags), 2 шестнадцатеричных символа. */
    public static final String TRACE_FLAGS = "traceFlags";

    /** Технический идентификатор запроса в MDC. */
    public static final String REQUEST_ID = "requestId";

    /** Бизнес-идентификатор корреляции в MDC. */
    public static final String CORRELATION_ID = "correlationId";

}
