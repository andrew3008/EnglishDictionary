package space.br1440.platform.errorhandling.core.constants;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

public final class ErrorHandlingHeaders {

    /**
     * Единственная wire-константа имени заголовка — {@link PlatformHeaders#X_CORRELATION_ID}.
     * Имя заголовка пересекает границу модулей {@code tracing}/{@code error-handling}
     * и не должно расходиться между ними.
     */
    public static final String X_CORRELATION_ID = PlatformHeaders.X_CORRELATION_ID;

    /** Идентификатор запроса (исторический; используется как fallback к correlation-id). */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /** Legacy Zipkin trace-id. */
    public static final String X_B3_TRACE_ID = "X-B3-TraceId";

    /** W3C Trace Context (RFC). */
    public static final String TRACEPARENT = "traceparent";

    /** Стандартный HTTP-заголовок Retry-After (RFC 7231). */
    public static final String RETRY_AFTER = "Retry-After";

    private ErrorHandlingHeaders() {
        throw new AssertionError("ErrorHandlingHeaders is a utility class and must not be instantiated.");
    }
}
