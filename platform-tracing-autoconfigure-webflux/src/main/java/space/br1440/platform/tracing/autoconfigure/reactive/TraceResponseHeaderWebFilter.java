package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Optional;

import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.RemoteServiceMdcBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdBoundarySupport;

/**
 * Реактивный фильтр, добавляющий correlation/trace заголовки в HTTP-ответ.
 * <p>
 * {@code X-Request-Id} — edge-stable correlation id (НЕ trace-id, см. ADR-request-id-correlation-id):
 * входящий валидируется и переиспользуется, при отсутствии генерируется UUIDv4. Авторитетный
 * trace-id возвращается в {@link PlatformHeaders#X_TRACE_ID}. Заголовки выставляются как
 * «before-commit» action, чтобы попасть в ответ до начала записи тела.
 */
public class TraceResponseHeaderWebFilter implements WebFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;

    public TraceResponseHeaderWebFilter(TraceOperations traceOperations, TracingProperties properties) {
        this.traceOperations = traceOperations;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        final Optional<String> capturedTraceId = safeCurrentTraceId();

        // Correlation id вычисляется на этапе filter() (request-thread): входящий или сгенерированный.
        final String incomingRequestId = exchange.getRequest().getHeaders()
                .getFirst(properties.getPropagation().getPlatformHeaders().getRequestIdHeader());
        final String correlationId = RequestIdBoundarySupport.resolve(incomingRequestId);

        response.beforeCommit(() -> Mono.fromRunnable(() -> {
            try {
                // X-Request-Id = correlation id (всегда присутствует).
                if (properties.getResponse().isExposeRequestIdHeader()) {
                    response.getHeaders().set(properties.getResponse().getHeaderName(), correlationId);
                }
                // X-Trace-Id = trace-id, только для валидного span context.
                traceOperations.traceContext().traceId()
                        .ifPresent(traceId -> response.getHeaders().set(PlatformHeaders.X_TRACE_ID, traceId));
            } catch (RuntimeException ignored) {
                // Любые ошибки трассировки не должны влиять на обработку запроса.
            }
        }));
        return chain.filter(exchange)
                .doFinally(signal -> RemoteServiceMdcBoundarySupport.clear(capturedTraceId.orElse(null)));
    }

    private Optional<String> safeCurrentTraceId() {
        try {
            return traceOperations.traceContext().traceId();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
