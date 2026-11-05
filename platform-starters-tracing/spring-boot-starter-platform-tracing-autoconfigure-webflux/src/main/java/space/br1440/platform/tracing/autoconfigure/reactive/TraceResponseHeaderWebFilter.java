package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.runtime.propagation.CorrelationIdSupport;
import space.br1440.platform.tracing.runtime.propagation.RequestIdSupport;

import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public class TraceResponseHeaderWebFilter implements WebFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;
    private final InboundTraceControlExtractor traceControlExtractor;
    private final AtomicBoolean headerReadFailureLogged = new AtomicBoolean();
    private final AtomicBoolean traceContextFailureLogged = new AtomicBoolean();
    private final AtomicBoolean requestAttributeFailureLogged = new AtomicBoolean();

    public TraceResponseHeaderWebFilter(TraceOperations traceOperations,
                                        TracingProperties properties,
                                        InboundTraceControlExtractor traceControlExtractor) {
        this.traceOperations = traceOperations;
        this.properties = properties;
        this.traceControlExtractor = traceControlExtractor;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        final String incomingRequestId = readHeader(exchange, PlatformHeaders.X_REQUEST_ID);
        final String requestId = RequestIdSupport.resolve(incomingRequestId);
        final String rawForceTrace = readHeader(exchange, PlatformHeaders.X_TRACE_ON);
        final String rawQaTrace = readHeader(exchange, PlatformHeaders.X_QA_TRACE);

        final InboundTraceControl traceControl = traceControlExtractor.fromHeaders(rawForceTrace, rawQaTrace, null);
        final String correlationId = resolveInboundCorrelationId(exchange);

        PlatformOwnedServerHttpResponse wrapped = new PlatformOwnedServerHttpResponse(exchange.getResponse());
        if (properties.getRequestIdHeader().isEnabled()) {
            wrapped.protectRequestId(PlatformHeaders.X_REQUEST_ID, requestId);
        }

        try {
            traceOperations.traceContext().traceId().ifPresent(wrapped::protectTraceId);
        } catch (RuntimeException failure) {
            warnOnce(traceContextFailureLogged, "Failed to read the current trace id: {}", failure);
        }

        wrapped.installCanonical();
        wrapped.beforeCommit(() -> Mono.fromRunnable(wrapped::installCanonical));
        ServerWebExchange filteredExchange = exchange.mutate().response(wrapped).build();
        return Mono.defer(() -> {
                    setRequestIdAttribute(requestId);
                    return chain.filter(filteredExchange);
                })
                .contextWrite(context -> context.put(
                        ReactiveIdentityContextPropagation.KEY,
                        new ReactiveIdentityContextPropagation.IdentityState(
                                requestId, correlationId, traceControl.forceTrace(), traceControl.qaTrace()
                        )
                ));
    }

    @Nullable
    private String readHeader(ServerWebExchange exchange, String name) {
        try {
            return exchange.getRequest().getHeaders().getFirst(name);
        } catch (RuntimeException failure) {
            warnOnce(headerReadFailureLogged, "Failed to read an inbound tracing header: {}", failure);
            return null;
        }
    }

    @Nullable
    private String resolveInboundCorrelationId(ServerWebExchange exchange) {
        if (!properties.getPropagation().getCorrelation().isAcceptInbound()) {
            return null;
        }

        try {
            return CorrelationIdSupport.resolveInbound(
                    exchange.getRequest().getHeaders().get(PlatformHeaders.X_CORRELATION_ID)
            );
        } catch (RuntimeException failure) {
            warnOnce(headerReadFailureLogged, "Failed to read an inbound tracing header: {}", failure);
            return null;
        }
    }

    private void setRequestIdAttribute(String requestId) {
        try {
            Span span = Span.current();
            if (span.getSpanContext().isValid()) {
                span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
            }
        } catch (RuntimeException failure) {
            warnOnce(requestAttributeFailureLogged, "Failed to set the request id span attribute: {}", failure);
        }
    }

    private static void warnOnce(AtomicBoolean logged, String message, RuntimeException failure) {
        if (logged.compareAndSet(false, true)) {
            log.warn(message, failure.getClass().getName());
        }
    }
}
