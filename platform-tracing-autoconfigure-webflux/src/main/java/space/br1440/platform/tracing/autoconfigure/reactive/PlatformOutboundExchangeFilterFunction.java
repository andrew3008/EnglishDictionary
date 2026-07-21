package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.Objects;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import reactor.core.publisher.Mono;

/**
 * Реактивный client-фильтр ({@code WebClient}), добавляющий платформенные управляющие заголовки
 * в исходящий запрос на доверенные хосты.
 * <p>
 * Agent-compatible: НЕ создаёт span'ы и НЕ инжектит W3C — только платформенные заголовки при
 * положительном trusted-решении (secure-by-default).
 *
 * <h3>Reactor Context</h3>
 * Логика обёрнута в {@link Mono#defer}, чтобы выполняться на этапе subscription (а не assembly),
 * когда {@code Hooks.enableAutomaticContextPropagation()} восстанавливает ThreadLocal'ы и
 * {@code Context.current()} содержит OTel Context (включая {@code TRACE_CONTROL}), пробрасываемый
 * Агентом через реактивную цепочку (в т.ч. после {@code publishOn}/thread-hop). Любой сбой
 * propagation изолируется и не влияет на исходящий запрос.
 */
public final class PlatformOutboundExchangeFilterFunction implements ExchangeFilterFunction {

    private final OutboundPropagationPolicy policy;
    private final PlatformOutboundPropagation propagation;

    public PlatformOutboundExchangeFilterFunction(OutboundPropagationPolicy policy,
                                                   PlatformOutboundPropagation propagation) {
        this.policy = policy;
        this.propagation = propagation;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> {
            try {
                String host = request.url().getHost();
                OutboundPropagationDecision decision = policy.decide(host);

                ClientRequest.Builder builder = ClientRequest.from(request);
                apply(builder, propagation.resolve(decision));
                return next.exchange(builder.build());
            } catch (RuntimeException ignored) {
                // Изоляция: сбой propagation не должен ломать исходящий вызов.
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
