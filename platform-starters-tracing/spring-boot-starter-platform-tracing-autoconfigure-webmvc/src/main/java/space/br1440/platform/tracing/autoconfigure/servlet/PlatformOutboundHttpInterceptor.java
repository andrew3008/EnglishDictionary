package space.br1440.platform.tracing.autoconfigure.servlet;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
            request.getHeaders().remove(PlatformHeaders.X_CORRELATION_ID);

            String host = request.getURI().getHost();
            OutboundPropagationDecision decision = policy.decide(host);
            InboundTraceControl control = new InboundTraceControl(
                    identityBoundary.forceTrace(), identityBoundary.qaTrace(), null, null, null
            );
            apply(request, propagation.resolve(decision, control));
        } catch (RuntimeException failure) {
            if (propagationFailureLogged.compareAndSet(false, true)) {
                log.warn("Failed to prepare platform propagation headers: {}", failure.getClass().getName());
            }
        }

        return execution.execute(request, body);
    }

    private static void apply(HttpRequest request, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> setHeader(request, header));
        headers.qaTrace().ifPresent(header -> setHeader(request, header));
        headers.requestId().ifPresent(header -> setHeader(request, header));
        headers.correlationId().ifPresent(header -> setHeader(request, header));
    }

    private static void setHeader(HttpRequest request, OutboundPropagationHeaders.Header header) {
        Objects.requireNonNull(header.value(), "value");
        request.getHeaders().set(header.name(), header.value());
    }
}
