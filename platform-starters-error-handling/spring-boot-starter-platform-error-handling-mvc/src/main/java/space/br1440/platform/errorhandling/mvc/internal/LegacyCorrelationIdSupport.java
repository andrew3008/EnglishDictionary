package space.br1440.platform.errorhandling.mvc.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.lang.NonNull;

import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;

import java.io.IOException;

final class LegacyCorrelationIdSupport {

    private final CorrelationIdResolver resolver;

    LegacyCorrelationIdSupport(@NonNull CorrelationIdResolver resolver) {
        this.resolver = resolver;
    }

    void handle(@NonNull HttpServletRequest request,
                @NonNull HttpServletResponse response,
                @NonNull FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolver.resolveFromRequest(request);
        request.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, correlationId);

        if (!response.isCommitted()
                && !response.containsHeader(ErrorHandlingHeaders.X_CORRELATION_ID)) {
            response.setHeader(ErrorHandlingHeaders.X_CORRELATION_ID, correlationId);
        }

        MDC.put(ErrorHandlingMdcKeys.CORRELATION_ID, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ErrorHandlingMdcKeys.CORRELATION_ID);
        }
    }
}
