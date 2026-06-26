package space.br1440.platform.errorhandling.core.constants;

public final class ErrorHandlingHeaders {

    /** Корреляционный идентификатор запроса (исторический). */
    public static final String X_CORRELATION_ID = "X-Correlation-Id";

    /** Идентификатор запроса (исторический; используется как fallback к correlation-id). */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /** Legacy Zipkin trace-id. */
    public static final String X_B3_TRACE_ID = "X-B3-TraceId";

    /** W3C Trace Context (RFC). */
    public static final String TRACEPARENT = "traceparent";

    /** Стандартный HTTP-заголовок Retry-After (RFC 7231). */
    public static final String RETRY_AFTER = "Retry-After";

    private ErrorHandlingHeaders() {
        throw new AssertionError("ErrorHandlingHeaders — utility-класс, инстанцирование запрещено.");
    }
}
