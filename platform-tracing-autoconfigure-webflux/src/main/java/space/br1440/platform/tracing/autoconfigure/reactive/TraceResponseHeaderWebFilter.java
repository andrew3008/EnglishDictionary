package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Optional;

import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.opentelemetry.api.trace.Span;

import reactor.core.publisher.Mono;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.support.RemoteServiceMdcBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdBoundarySupport;

/**
 * Реактивный фильтр, добавляющий request/trace заголовки в HTTP-ответ.
 * <p>
 * {@code X-Request-Id} — edge-stable requestId (НЕ traceId и НЕ business correlationId):
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

        // Request id вычисляется один раз на границе: входящий или сгенерированный.
        final String incomingRequestId = exchange.getRequest().getHeaders()
                .getFirst(properties.getPropagation().getPlatformHeaders().getRequestIdHeader());
        final String requestId = RequestIdBoundarySupport.resolve(incomingRequestId);

        response.beforeCommit(() -> Mono.fromRunnable(() -> {
            try {
                // X-Request-Id всегда присутствует.
                if (properties.getResponse().isExposeRequestIdHeader()) {
                    response.getHeaders().set(properties.getResponse().getHeaderName(), requestId);
                }
                // X-Trace-Id = trace-id, только для валидного span context.
                traceOperations.traceContext().traceId()
                        .ifPresent(traceId -> response.getHeaders().set(PlatformHeaders.X_TRACE_ID, traceId));
            } catch (RuntimeException ignored) {
                // Любые ошибки трассировки не должны влиять на обработку запроса.
            }
        }));
        return Mono.defer(() -> {
                    try {
                        Span span = Span.current();
                        if (span.getSpanContext().isValid()) {
                            span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
                        }
                    } catch (RuntimeException ignored) {
                        // Ошибки трассировки не влияют на обработку запроса.
                    }
                    return chain.filter(exchange);
                })
                .doFinally(signal -> RemoteServiceMdcBoundarySupport.clear(capturedTraceId.orElse(null)))
                .contextWrite(context -> context.put(
                        ReactiveIdentityContextPropagation.KEY,
                        context.getOrDefault(
                                        ReactiveIdentityContextPropagation.KEY,
                                        ReactiveIdentityContextPropagation.EMPTY)
                                .withRequestId(requestId)
                ));
    }

    private Optional<String> safeCurrentTraceId() {
        try {
            return traceOperations.traceContext().traceId();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
