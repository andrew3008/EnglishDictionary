package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.Nonnull;

import org.springframework.http.HttpHeaders;
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
            ClientRequest.Builder builder = ClientRequest.from(request)
                    .headers(PlatformOutboundExchangeFilterFunction::strip);

            try {
                String host = request.url().getHost();
                OutboundPropagationDecision decision = policy.decide(host);

                ReactiveIdentityContextPropagation.IdentityState identityState = contextView
                        .<ReactiveIdentityContextPropagation.IdentityState>getOrEmpty(ReactiveIdentityContextPropagation.KEY)
                        .orElse(ReactiveIdentityContextPropagation.EMPTY);

                InboundTraceControl control = new InboundTraceControl(
                        identityState.forceTrace(), identityState.qaTrace(), null, null, null
                );

                OutboundPropagationHeaders headers = propagation.resolve(decision, control);
                builder.headers(carrier -> apply(carrier, headers));
            } catch (RuntimeException failure) {
                if (propagationFailureLogged.compareAndSet(false, true)) {
                    log.warn("Failed to prepare platform propagation headers: {}", failure.getClass().getName());
                }
            }

            return next.exchange(builder.build());
        });
    }

    private static void strip(HttpHeaders carrier) {
        PlatformHeaders.OUTBOUND_CONTROL_HEADERS.forEach(carrier::remove);
    }

    private static void apply(HttpHeaders carrier, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(value -> carrier.set(PlatformHeaders.X_TRACE_ON, value));
        headers.qaTrace().ifPresent(value -> carrier.set(PlatformHeaders.X_QA_TRACE, value));
        headers.requestId().ifPresent(value -> carrier.set(PlatformHeaders.X_REQUEST_ID, value));
        headers.correlationId().ifPresent(value -> carrier.set(PlatformHeaders.X_CORRELATION_ID, value));
    }
}
