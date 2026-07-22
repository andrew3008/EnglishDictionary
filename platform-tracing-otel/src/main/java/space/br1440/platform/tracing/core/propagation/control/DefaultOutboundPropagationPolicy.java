package space.br1440.platform.tracing.core.propagation.control;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

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
