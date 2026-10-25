package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.Nonnull;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public final class PlatformOutboundExchangeFilterFunction implements ExchangeFilterFunction {

    private final OutboundPropagationPolicy policy;
    private final PlatformOutboundPropagation propagation;
    private final AtomicBoolean propagationFailureLogged = new AtomicBoolean();

    public PlatformOutboundExchangeFilterFunction(OutboundPropagationPolicy policy,
                                                  PlatformOutboundPropagation propagation) {
        this.policy = policy;
        this.propagation = propagation;
    }

    @Override
    @Nonnull
    public Mono<ClientResponse> filter(@Nonnull ClientRequest request, @Nonnull ExchangeFunction next) {
        return Mono.deferContextual(contextView -> {
            try {
                String host = request.url().getHost();
                OutboundPropagationDecision decision = policy.decide(host);

                ReactiveIdentityContextPropagation.IdentityState identityState = contextView
                        .<ReactiveIdentityContextPropagation.IdentityState>getOrEmpty(ReactiveIdentityContextPropagation.KEY)
                        .orElse(ReactiveIdentityContextPropagation.EMPTY);
                InboundTraceControl control = new InboundTraceControl(
                        identityState.forceTrace(), identityState.qaTrace(), null, null, null
                );

                ClientRequest.Builder builder = ClientRequest.from(request);
                builder.headers(httpHeaders -> httpHeaders.remove(PlatformHeaders.X_CORRELATION_ID));
                apply(builder, propagation.resolve(decision, control));
                return next.exchange(builder.build());
            } catch (RuntimeException failure) {
                if (propagationFailureLogged.compareAndSet(false, true)) {
                    log.warn("Failed to prepare platform propagation headers: {}", failure.getClass().getName());
                }
                return next.exchange(request);
            }
        });
    }

    private static void apply(ClientRequest.Builder builder, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> setHeader(builder, header));
        headers.qaTrace().ifPresent(header -> setHeader(builder, header));
        headers.requestId().ifPresent(header -> setHeader(builder, header));
        headers.correlationId().ifPresent(header -> setHeader(builder, header));
    }

    private static void setHeader(ClientRequest.Builder builder, OutboundPropagationHeaders.Header header) {
        Objects.requireNonNull(header.value(), "value");
        builder.header(header.name(), header.value());
    }
}
