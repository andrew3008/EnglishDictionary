package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Objects;

import jakarta.annotation.Nonnull;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import reactor.core.publisher.Mono;

public final class PlatformOutboundExchangeFilterFunction implements ExchangeFilterFunction {

    private final OutboundPropagationPolicy policy;
    private final PlatformOutboundPropagation propagation;

    public PlatformOutboundExchangeFilterFunction(OutboundPropagationPolicy policy,
                                                   PlatformOutboundPropagation propagation) {
        this.policy = policy;
        this.propagation = propagation;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(@Nonnull ClientRequest request, @Nonnull ExchangeFunction next) {
        return Mono.defer(() -> {
            try {
                String host = request.url().getHost();
                OutboundPropagationDecision decision = policy.decide(host);

                ClientRequest.Builder builder = ClientRequest.from(request);
                apply(builder, propagation.resolve(decision));
                return next.exchange(builder.build());
            } catch (RuntimeException ignored) {
                return next.exchange(request);
            }
        });
    }

    private static void apply(ClientRequest.Builder builder, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> setHeader(builder, header));
        headers.qaTrace().ifPresent(header -> setHeader(builder, header));
        headers.requestId().ifPresent(header -> setHeader(builder, header));
    }

    private static void setHeader(ClientRequest.Builder builder, OutboundPropagationHeaders.Header header) {
        Objects.requireNonNull(header.value(), "value");
        builder.header(header.name(), header.value());
    }
}
