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
import org.springframework.web.filter.OncePerRequestFilter;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.api.span.SpanResult;
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
    private final AtomicBoolean traceContextFailureLogged = new AtomicBoolean();
    private final AtomicBoolean requestAttributeFailureLogged = new AtomicBoolean();
    private final AtomicBoolean resultAttributeFailureLogged = new AtomicBoolean();

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

        final String incomingRequestId = request.getHeader(PlatformHeaders.X_REQUEST_ID);
        final String requestId = RequestIdSupport.resolve(incomingRequestId);
        final String rawForceTrace = request.getHeader(PlatformHeaders.X_TRACE_ON);
        final String rawQaTrace = request.getHeader(PlatformHeaders.X_QA_TRACE);

        final InboundTraceControl traceControl = traceControlExtractor.fromHeaders(rawForceTrace, rawQaTrace, null);

        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(response);
        if (properties.getRequestIdHeader().isEnabled()) {
            wrapped.protectRequestId(PlatformHeaders.X_REQUEST_ID, requestId);
        }
        capturedTraceId.ifPresent(wrapped::protectTraceId);

        Throwable failure = null;
        try (CorrelationScope ignored = identityBoundary.openRequestScope(requestId, traceControl.forceTrace(), traceControl.qaTrace());
             CorrelationScope ignoredCorrelation = openInboundCorrelationScope(request)) {
            try {
                Span span = Span.current();
                if (span.getSpanContext().isValid()) {
                    span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
                }
            } catch (RuntimeException attributeFailure) {
                warnOnce(
                        requestAttributeFailureLogged,
                        "Failed to set the request id span attribute: {}",
                        attributeFailure
                );
            }

            if (!response.isCommitted()) {
                wrapped.installCanonical();
            }

            try {
                filterChain.doFilter(request, wrapped);
            } catch (ServletException | IOException | RuntimeException | Error ex) {
                failure = ex;
                throw ex;
            }
        } finally {
            applyHttpServerResult(wrapped, failure);
        }
    }

    private void applyHttpServerResult(HttpServletResponse response, Throwable failure) {
        try {
            Span span = Span.current();
            if (!span.getSpanContext().isValid()) {
                return;
            }

            SpanResult result = (failure != null || response.getStatus() >= 400)
                    ? SpanResult.FAILURE
                    : SpanResult.SUCCESS;
            span.setAttribute(PlatformAttributes.PLATFORM_RESULT, result.value());
        } catch (RuntimeException resultFailure) {
            warnOnce(
                    resultAttributeFailureLogged,
                    "Failed to set the HTTP server result span attribute: {}",
                    resultFailure
            );
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
