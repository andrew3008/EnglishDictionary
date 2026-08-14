package space.br1440.platform.tracing.autoconfigure.servlet;

import java.io.IOException;
import java.util.Objects;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

public final class PlatformOutboundHttpInterceptor implements ClientHttpRequestInterceptor {

    private final OutboundPropagationPolicy policy;
    private final PlatformOutboundPropagation propagation;

    public PlatformOutboundHttpInterceptor(OutboundPropagationPolicy policy,
                                           PlatformOutboundPropagation propagation) {
        this.policy = policy;
        this.propagation = propagation;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        try {
            String host = request.getURI().getHost();
            OutboundPropagationDecision decision = policy.decide(host);
            apply(request, propagation.resolve(decision));
        } catch (RuntimeException ignored) {
        }

        return execution.execute(request, body);
    }

    private static void apply(HttpRequest request, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> setHeader(request, header));
        headers.qaTrace().ifPresent(header -> setHeader(request, header));
        headers.requestId().ifPresent(header -> setHeader(request, header));
    }

    private static void setHeader(HttpRequest request, OutboundPropagationHeaders.Header header) {
        Objects.requireNonNull(header.value(), "value");
        request.getHeaders().set(header.name(), header.value());
    }
}
