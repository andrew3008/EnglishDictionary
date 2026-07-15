package space.br1440.platform.tracing.core.propagation.control;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

/**
 * Реализация {@link OutboundPropagationPolicy} по умолчанию.
 * <p>
 * Secure-by-default: если propagation выключен или destination не доверенный — возвращает {@link OutboundPropagationDecision#DENY_ALL}.
 * Прикладной код использует этот класс через Spring {@code @Bean}; прямой {@code new} допустим только в autoconfigure и otel-extension.
 */
@RequiredArgsConstructor
public final class DefaultOutboundPropagationPolicy implements OutboundPropagationPolicy {

    private final boolean enabled;
    private final TrustedDestinationMatcher trusted;
    private final boolean propagateForceTrace;
    private final boolean propagateQaTrace;
    private final boolean propagateRequestId;

    @Override
    public OutboundPropagationDecision decide(String destination) {
        if (!enabled || trusted == null || !trusted.isTrusted(destination)) {
            return OutboundPropagationDecision.DENY_ALL;
        }

        return new OutboundPropagationDecision(propagateForceTrace, propagateQaTrace, propagateRequestId);
    }
}
