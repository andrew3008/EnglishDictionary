package space.br1440.platform.errorhandling.core.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.util.correlation.CorrelationIdHttpInboundResolver;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

/**
 * Резолвер correlation-id для входящего HTTP-запроса (Servlet).
 */
public final class CorrelationIdResolver {

    private final CorrelationIdHttpInboundResolver inbound;

    public CorrelationIdResolver(@NonNull ErrorHandlingProperties.CorrelationId correlationIdConfig,
                                 boolean acceptTraceparent) {
        this.inbound = new CorrelationIdHttpInboundResolver(correlationIdConfig, acceptTraceparent);
    }

    @NonNull
    public String resolveFromRequest(@NonNull HttpServletRequest request) {
        return inbound.resolve(request::getHeader);
    }
}
