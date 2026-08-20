package space.br1440.platform.errorhandling.mvc.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.model.RequestContext;

import java.io.IOException;
import java.util.function.Supplier;

public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

    private final LegacyCorrelationIdSupport legacySupport;

    @Nullable
    private final Supplier<RequestContext> tracingRequestContextSupplier;

    public CorrelationIdFilter(@NonNull CorrelationIdResolver resolver,
                               @Nullable Supplier<RequestContext> tracingRequestContextSupplier) {
        this.legacySupport = new LegacyCorrelationIdSupport(resolver);
        this.tracingRequestContextSupplier = tracingRequestContextSupplier;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (tracingRequestContextSupplier != null) {
            String correlationId = tracingRequestContextSupplier.get().correlationId();
            request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId == null ? "" : correlationId);
            filterChain.doFilter(request, response);
            return;
        }

        legacySupport.handle(request, response, filterChain);
    }
}
