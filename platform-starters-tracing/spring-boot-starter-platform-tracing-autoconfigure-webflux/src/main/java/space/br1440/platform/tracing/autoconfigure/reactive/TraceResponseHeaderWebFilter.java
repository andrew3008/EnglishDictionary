package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpStatusCode;
import org.springframework.lang.NonNull;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.integrations.reactor.OtelReactorContextPropagation;
import space.br1440.platform.tracing.runtime.propagation.CorrelationIdSupport;
import space.br1440.platform.tracing.runtime.propagation.RequestIdSupport;

import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;

@Slf4j
public class TraceResponseHeaderWebFilter implements WebFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;
    private final InboundTraceControlExtractor traceControlExtractor;
    private final AtomicBoolean traceContextFailureLogged = new AtomicBoolean();
    private final AtomicBoolean requestAttributeFailureLogged = new AtomicBoolean();
    private final AtomicBoolean resultAttributeFailureLogged = new AtomicBoolean();

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
        final String incomingRequestId = exchange.getRequest().getHeaders().getFirst(PlatformHeaders.X_REQUEST_ID);
        final String requestId = RequestIdSupport.resolve(incomingRequestId);
        final String rawForceTrace = exchange.getRequest().getHeaders().getFirst(PlatformHeaders.X_TRACE_ON);
        final String rawQaTrace = exchange.getRequest().getHeaders().getFirst(PlatformHeaders.X_QA_TRACE);

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
                    try {
                        Span span = Span.current();
                        if (span.getSpanContext().isValid()) {
                            span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, requestId);
                        }
                    } catch (RuntimeException failure) {
                        warnOnce(
                                requestAttributeFailureLogged,
                                "Failed to set the request id span attribute: {}",
                                failure
                        );
                    }

                    return chain.filter(filteredExchange);
                })
                .doFinally(signal -> {
                    applyHttpServerResult(wrapped, signal == SignalType.ON_ERROR);
                    OtelReactorContextPropagation.detachEndedCurrentContext();
                })
                .contextWrite(context -> {
                    ReactiveIdentityContextPropagation.IdentityState currentState = context
                            .<ReactiveIdentityContextPropagation.IdentityState>getOrEmpty(ReactiveIdentityContextPropagation.KEY)
                            .orElse(ReactiveIdentityContextPropagation.EMPTY);

                    Context written = context.put(
                            ReactiveIdentityContextPropagation.KEY,
                            currentState
                                    .withRequestId(requestId)
                                    .withTraceControl(traceControl.forceTrace(), traceControl.qaTrace())
                                    .withCorrelationId(correlationId)
                    );

                    Object otelSnapshot = OtelReactorContextPropagation.snapshotRecordingContext();
                    if (otelSnapshot != null) {
                        written = written.put(OtelReactorContextPropagation.reactorContextKey(), otelSnapshot);
                    }

                    return written;
                });
    }

    private String resolveInboundCorrelationId(ServerWebExchange exchange) {
        if (!properties.getPropagation().getCorrelation().isAcceptInbound()) {
            return null;
        }

        return CorrelationIdSupport.resolveInbound(
                exchange.getRequest().getHeaders().get(PlatformHeaders.X_CORRELATION_ID)
        );
    }

    private void applyHttpServerResult(PlatformOwnedServerHttpResponse response, boolean failed) {
        try {
            Span span = Span.current();
            if (!span.getSpanContext().isValid()) {
                return;
            }

            HttpStatusCode statusCode = response.getStatusCode();
            boolean errorStatus = (statusCode != null) && (statusCode.value() >= 400);
            SpanResult result = (failed || errorStatus) ? SpanResult.FAILURE : SpanResult.SUCCESS;
            span.setAttribute(PlatformAttributes.PLATFORM_RESULT, result.value());
        } catch (RuntimeException failure) {
            warnOnce(
                    resultAttributeFailureLogged,
                    "Failed to set the HTTP server result span attribute: {}",
                    failure
            );
        }
    }

    private static void warnOnce(AtomicBoolean logged, String message, RuntimeException failure) {
        if (logged.compareAndSet(false, true)) {
            log.warn(message, failure.getClass().getName());
        }
    }
}
