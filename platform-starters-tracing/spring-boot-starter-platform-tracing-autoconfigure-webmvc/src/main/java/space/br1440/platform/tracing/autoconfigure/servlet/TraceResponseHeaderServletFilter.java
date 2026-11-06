package space.br1440.platform.tracing.autoconfigure.servlet;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.runtime.propagation.CorrelationIdSupport;
import space.br1440.platform.tracing.runtime.propagation.RequestIdSupport;

import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TraceResponseHeaderServletFilter extends OncePerRequestFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;
    private final RequestIdentityBoundarySupport identityBoundary;
    private final InboundTraceControlExtractor traceControlExtractor;
    private final AtomicBoolean headerReadFailureLogged = new AtomicBoolean();
    private final AtomicBoolean correlationHeaderReadFailureLogged = new AtomicBoolean();
    private final AtomicBoolean traceContextFailureLogged = new AtomicBoolean();
    private final AtomicBoolean requestAttributeFailureLogged = new AtomicBoolean();

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

        final String incomingRequestId = readHeader(request, PlatformHeaders.X_REQUEST_ID);
        final String requestId = RequestIdSupport.resolve(incomingRequestId);
        final String rawForceTrace = readHeader(request, PlatformHeaders.X_TRACE_ON);
        final String rawQaTrace = readHeader(request, PlatformHeaders.X_QA_TRACE);

        final InboundTraceControl traceControl = traceControlExtractor.fromHeaders(rawForceTrace, rawQaTrace, null);

        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(response);
        if (properties.getRequestIdHeader().isEnabled()) {
            wrapped.protectRequestId(PlatformHeaders.X_REQUEST_ID, requestId);
        }
        capturedTraceId.ifPresent(wrapped::protectTraceId);

        String correlationId = resolveInboundCorrelationId(request);
        try (CorrelationScope ignored = identityBoundary.openRequestScope(requestId, traceControl.forceTrace(), traceControl.qaTrace());
             CorrelationScope ignoredCorrelation = openCorrelationScope(correlationId)) {
            setIdentityAttributes(requestId, correlationId);

            if (!response.isCommitted()) {
                wrapped.installCanonical();
            }

            filterChain.doFilter(request, wrapped);
        }
    }

    private void setIdentityAttributes(String requestId, @Nullable String correlationId) {
        try {
            Span span = Span.current();
            if (span.getSpanContext().isValid()) {
                span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
                if (correlationId != null) {
                    span.setAttribute(PlatformAttributes.PLATFORM_CORRELATION_ID, correlationId);
                }
            }
        } catch (RuntimeException attributeFailure) {
            warnOnce(requestAttributeFailureLogged, "Failed to set the request identity span attributes: {}", attributeFailure);
        }
    }

    @Nullable
    private String resolveInboundCorrelationId(HttpServletRequest request) {
        if (!properties.getPropagation().getCorrelation().isAcceptInbound()) {
            return null;
        }
        return CorrelationIdSupport.resolveInbound(readCorrelationHeaders(request));
    }

    private CorrelationScope openCorrelationScope(@Nullable String correlationId) {
        return (correlationId == null) ? () -> { } : identityBoundary.openCorrelationScope(correlationId);
    }

    @Nullable
    private String readHeader(HttpServletRequest request, String name) {
        try {
            return request.getHeader(name);
        } catch (RuntimeException failure) {
            warnOnce(headerReadFailureLogged, "Failed to read an inbound tracing header: {}", failure);
            return null;
        }
    }

    private List<String> readCorrelationHeaders(HttpServletRequest request) {
        try {
            Enumeration<String> values = request.getHeaders(PlatformHeaders.X_CORRELATION_ID);
            return (values == null) ? List.of() : Collections.list(values);
        } catch (RuntimeException failure) {
            warnOnce(correlationHeaderReadFailureLogged,
                    "Failed to read inbound header X-Correlation-Id: {}", failure);
            return List.of();
        }
    }

    private Optional<String> safeCurrentTraceId() {
        try {
            return traceOperations.traceContext().traceId();
        } catch (RuntimeException failure) {
            warnOnce(traceContextFailureLogged, "Failed to read the current trace id: {}", failure);
            return Optional.empty();
        }
    }

    private static void warnOnce(AtomicBoolean logged, String message, RuntimeException failure) {
        if (logged.compareAndSet(false, true)) {
            log.warn(message, failure.getClass().getName());
        }
    }
}
