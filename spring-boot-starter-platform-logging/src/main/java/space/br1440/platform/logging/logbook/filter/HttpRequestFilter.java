package space.br1440.platform.logging.logbook.filter;

import org.jspecify.annotations.NullMarked;
import org.slf4j.MDC;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.RequestFilter;
import space.br1440.platform.logging.mdc.MDCLogKey;

import java.util.UUID;

@NullMarked
public class HttpRequestFilter implements RequestFilter {

    @Override
    public HttpRequest filter(HttpRequest request) {
        MDC.put(MDCLogKey.START_TIME.value(), String.valueOf(System.currentTimeMillis()));
        MDC.put(MDCLogKey.URI.value(), request.getRequestUri());
        MDC.put(MDCLogKey.HTTP_METHOD.value(), request.getMethod());

        String correlationId = request.getHeaders().getFirst("X-Correlation-Id");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        String userAgent = request.getHeaders().getFirst("User-Agent");
        if (userAgent != null) {
            MDC.put(MDCLogKey.HTTP_USER_AGENT.getValue(), userAgent);
        }

        MDC.put(MDCLogKey.REQUEST_ID.value(), correlationId);

        return request;
    }
}