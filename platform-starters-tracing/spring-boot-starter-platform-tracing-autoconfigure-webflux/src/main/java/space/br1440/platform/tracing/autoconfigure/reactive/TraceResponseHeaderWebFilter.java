package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdBoundarySupport;

import io.opentelemetry.api.trace.Span;
import reactor.core.publisher.Mono;

public class TraceResponseHeaderWebFilter implements WebFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;

    public TraceResponseHeaderWebFilter(TraceOperations traceOperations, TracingProperties properties) {
        this.traceOperations = traceOperations;
        this.properties = properties;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();

        final String incomingRequestId = exchange.getRequest().getHeaders()
                .getFirst(properties.getPropagation().getPlatformHeaders().getRequestIdHeader());
        final String requestId = RequestIdBoundarySupport.resolve(incomingRequestId);

        response.beforeCommit(() -> Mono.fromRunnable(() -> {
            try {
                if (properties.getRequestIdHeader().isEnabled()) {
                    response.getHeaders().set(properties.getRequestIdHeader().getName(), requestId);
                }

                traceOperations.traceContext().traceId()
                        .ifPresent(traceId -> response.getHeaders().set(PlatformHeaders.X_TRACE_ID, traceId));
            } catch (RuntimeException ignored) {
            }
        }));
        return Mono.defer(() -> {
                    try {
                        Span span = Span.current();
                        if (span.getSpanContext().isValid()) {
                            span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
                        }
                    } catch (RuntimeException ignored) {
                    }

                    return chain.filter(exchange);
                })
                .contextWrite(context -> {
                    ReactiveIdentityContextPropagation.IdentityState currentState = context
                            .<ReactiveIdentityContextPropagation.IdentityState>getOrEmpty(ReactiveIdentityContextPropagation.KEY)
                            .orElse(ReactiveIdentityContextPropagation.EMPTY);
                    return context.put(
                            ReactiveIdentityContextPropagation.KEY,
                            currentState.withRequestId(requestId)
                    );
                });
    }
}
