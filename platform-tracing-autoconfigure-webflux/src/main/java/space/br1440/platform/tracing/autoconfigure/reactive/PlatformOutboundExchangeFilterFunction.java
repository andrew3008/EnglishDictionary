package space.br1440.platform.tracing.autoconfigure.reactive;

import io.opentelemetry.context.Context;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundInjector;
import space.br1440.platform.tracing.api.propagation.control.PlatformPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;

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
    private final PlatformOutboundInjector injector;

    public PlatformOutboundExchangeFilterFunction(OutboundPropagationPolicy policy, PlatformOutboundInjector injector) {
        this.policy = policy;
        this.injector = injector;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> {
            try {
                Context otel = Context.current();
                String host = request.url().getHost();
                PlatformPropagationDecision decision = policy.decide(host);
                Context decided = otel.with(PlatformTraceContextKeys.PROPAGATION_DECISION, decision);

                ClientRequest.Builder builder = ClientRequest.from(request);
                injector.inject(decided, builder, PlatformClientRequestBuilderSetter.INSTANCE);
                return next.exchange(builder.build());
            } catch (RuntimeException ignored) {
                // Изоляция: сбой propagation не должен ломать исходящий вызов.
                return next.exchange(request);
            }
        });
    }
}
