package space.br1440.platform.tracing.autoconfigure.servlet;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;

import java.io.IOException;
import java.util.Optional;

public class TraceResponseHeaderServletFilter extends OncePerRequestFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;
    private final RequestIdentityBoundarySupport identityBoundary;

    public TraceResponseHeaderServletFilter(TraceOperations traceOperations,
                                            TracingProperties properties,
                                            RequestIdentityBoundarySupport identityBoundary) {
        this.traceOperations = traceOperations;
        this.properties = properties;
        this.identityBoundary = identityBoundary;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        final Optional<String> capturedTraceId = safeCurrentTraceId();

        final String incomingRequestId = request.getHeader(
                properties.getPropagation().getPlatformHeaders().getRequestIdHeader());
        final String requestId = RequestIdBoundarySupport.resolve(incomingRequestId);

        try (CorrelationScope ignored = identityBoundary.openRequestScope(requestId)) {
            try {
                Span span = Span.current();
                if (span.getSpanContext().isValid()) {
                    span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
                }
            } catch (RuntimeException ignoredFailure) {
            }

            try {
                filterChain.doFilter(request, response);
            } finally {
                if (!response.isCommitted()) {
                    try {
                        if (properties.getRequestIdHeader().isEnabled()) {
                            response.setHeader(properties.getRequestIdHeader().getName(), requestId);
                        }

                        capturedTraceId.ifPresent(traceId -> response.setHeader(PlatformHeaders.X_TRACE_ID, traceId));
                    } catch (RuntimeException ignoredFailure) {
                    }
                }
            }
        }
    }

    private Optional<String> safeCurrentTraceId() {
        try {
            return traceOperations.traceContext().traceId();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
