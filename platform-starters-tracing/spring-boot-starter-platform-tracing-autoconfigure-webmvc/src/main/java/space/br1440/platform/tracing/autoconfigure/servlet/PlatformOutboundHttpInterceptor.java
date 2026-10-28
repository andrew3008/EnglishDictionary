package space.br1440.platform.tracing.autoconfigure.servlet;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PlatformOutboundHttpInterceptor implements ClientHttpRequestInterceptor {

    private final OutboundPropagationPolicy policy;
    private final PlatformOutboundPropagation propagation;
    private final RequestIdentityBoundarySupport identityBoundary;
    private final AtomicBoolean propagationFailureLogged = new AtomicBoolean();

    public PlatformOutboundHttpInterceptor(OutboundPropagationPolicy policy,
                                           PlatformOutboundPropagation propagation,
                                           RequestIdentityBoundarySupport identityBoundary) {
        this.policy = policy;
        this.propagation = propagation;
        this.identityBoundary = identityBoundary;
    }

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request,
                                        @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution) throws IOException {
        try {
            PlatformHeaders.OUTBOUND_CONTROL_HEADERS.forEach(request.getHeaders()::remove);

            String host = request.getURI().getHost();
            OutboundPropagationDecision decision = policy.decide(host);
            InboundTraceControl control = new InboundTraceControl(
                    identityBoundary.forceTrace(), identityBoundary.qaTrace(), null, null, null
            );
            apply(request.getHeaders(), propagation.resolve(decision, control));
        } catch (RuntimeException failure) {
            if (propagationFailureLogged.compareAndSet(false, true)) {
                log.warn("Failed to prepare platform propagation headers: {}", failure.getClass().getName());
            }
        }

        return execution.execute(request, body);
    }

    private static void apply(HttpHeaders carrier, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(value -> carrier.set(PlatformHeaders.X_TRACE_ON, value));
        headers.qaTrace().ifPresent(value -> carrier.set(PlatformHeaders.X_QA_TRACE, value));
        headers.requestId().ifPresent(value -> carrier.set(PlatformHeaders.X_REQUEST_ID, value));
        headers.correlationId().ifPresent(value -> carrier.set(PlatformHeaders.X_CORRELATION_ID, value));
    }
}
