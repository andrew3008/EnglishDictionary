package space.br1440.platform.errorhandling.core.util.correlation;

import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;

import java.util.List;

/**
 * Единая цепочка имён HTTP/STOMP заголовков после опционально обработанного {@code traceparent}.
 *
 * <p>Менять порядок только здесь: {@link CorrelationIdHttpInboundResolver},
 * STOMP-адаптер и любые последующие резолверы должны использовать {@link #AFTER_TRACEPARENT},
 * чтобы не было расхождения платформы между Servlet, WebFlux и messaging.
 */
public final class CorrelationIdFallbackHeaderChain {

    /**
     * Неизменяемый порядок: корреляция → legacy request-id → B3 trace-id.
     */
    @NonNull
    public static final List<String> AFTER_TRACEPARENT = List.of(
            ErrorHandlingHeaders.X_CORRELATION_ID,
            ErrorHandlingHeaders.X_REQUEST_ID,
            ErrorHandlingHeaders.X_B3_TRACE_ID
    );

    private CorrelationIdFallbackHeaderChain() {}
}
