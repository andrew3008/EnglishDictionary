package space.br1440.platform.errorhandling.core.util;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.util.correlation.CorrelationIdHttpInboundResolver;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

/**
 * Резолвер correlation-id из входящего reactive HTTP-запроса.
 */
public final class ReactiveCorrelationIdResolver {

    private static final ReactiveCorrelationIdResolver DEFAULT_INSTANCE =
            new ReactiveCorrelationIdResolver(new ErrorHandlingProperties.CorrelationId(), true);

    private final CorrelationIdHttpInboundResolver inbound;

    public ReactiveCorrelationIdResolver(@NonNull ErrorHandlingProperties.CorrelationId correlationIdConfig,
                                         boolean acceptTraceparent) {
        this.inbound = new CorrelationIdHttpInboundResolver(correlationIdConfig, acceptTraceparent);
    }

    @NonNull
    public static String resolve(@NonNull ServerHttpRequest request) {
        return DEFAULT_INSTANCE.resolveFromRequest(request);
    }

    @NonNull
    public static ReactiveCorrelationIdResolver from(@NonNull ErrorHandlingProperties properties) {
        return new ReactiveCorrelationIdResolver(
                properties.getCorrelationId(),
                properties.getTracing().isAcceptTraceparent()
        );
    }

    @NonNull
    public String resolveFromRequest(@NonNull ServerHttpRequest request) {
        return inbound.resolve(name -> request.getHeaders().getFirst(name));
    }
}
