package space.br1440.platform.tracing.autoconfigure.servlet;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.runtime.propagation.CorrelationIdSupport;

import io.opentelemetry.api.trace.Span;

public class TraceResponseHeaderServletFilter extends OncePerRequestFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;
    private final RequestIdentityBoundarySupport identityBoundary;
    private final InboundTraceControlExtractor traceControlExtractor;

    public TraceResponseHeaderServletFilter(TraceOperations traceOperations,
                                            TracingProperties properties,
                                            RequestIdentityBoundarySupport identityBoundary,
                                            InboundTraceControlExtractor traceControlExtractor) {
        this.traceOperations = traceOperations;
        this.properties = properties;
        this.identityBoundary = identityBoundary;
        this.traceControlExtractor = traceControlExtractor;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        final Optional<String> capturedTraceId = safeCurrentTraceId();

        final String incomingRequestId = request.getHeader(
                properties.getPropagation().getPlatformHeaders().getRequestIdHeader()
        );
        final String requestId = RequestIdBoundarySupport.resolve(incomingRequestId);

        final String rawForceTrace = request.getHeader(
                properties.getPropagation().getPlatformHeaders().getForceTraceHeader()
        );

        final String rawQaTrace = request.getHeader(
                properties.getPropagation().getPlatformHeaders().getQaTraceHeader()
        );

        final InboundTraceControl traceControl = traceControlExtractor.fromHeaders(rawForceTrace, rawQaTrace, null);

        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(response);
        if (properties.getRequestIdHeader().isEnabled()) {
            wrapped.protectRequestId(properties.getRequestIdHeader().getName(), requestId);
        }
        capturedTraceId.ifPresent(wrapped::protectTraceId);

        try (CorrelationScope ignored = identityBoundary.openRequestScope(requestId, traceControl.forceTrace(), traceControl.qaTrace());
             CorrelationScope ignoredCorrelation = openInboundCorrelationScope(request)) {
            try {
                Span span = Span.current();
                if (span.getSpanContext().isValid()) {
                    span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
                }
            } catch (RuntimeException ignoredFailure) {
            }

            if (!response.isCommitted()) {
                wrapped.installCanonical();
            }
            filterChain.doFilter(request, wrapped);
        }
    }

    private CorrelationScope openInboundCorrelationScope(HttpServletRequest request) {
        if (!properties.getPropagation().getCorrelation().isAcceptInbound()) {
            return () -> { };
        }

        String correlationId = CorrelationIdSupport.resolveInbound(headerValues(request, PlatformHeaders.X_CORRELATION_ID));
        return (correlationId == null) ? () -> { } : identityBoundary.openCorrelationScope(correlationId);
    }

    @SuppressWarnings("SameParameterValue")
    private static List<String> headerValues(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null) {
            return Collections.emptyList();
        }

        return Collections.list(values);
    }

    private Optional<String> safeCurrentTraceId() {
        try {
            return traceOperations.traceContext().traceId();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
