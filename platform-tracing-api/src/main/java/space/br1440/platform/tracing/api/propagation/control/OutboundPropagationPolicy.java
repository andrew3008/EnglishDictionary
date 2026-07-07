package space.br1440.platform.tracing.api.propagation.control;

import lombok.RequiredArgsConstructor;

/**
 * Политика принятия решения об исходящей передаче платформенных заголовков на конкретный destination.
 */
@RequiredArgsConstructor
public final class OutboundPropagationPolicy {

    private final boolean enabled;
    private final TrustedDestinationMatcher trusted;
    private final boolean propagateForceTrace;
    private final boolean propagateQaTrace;
    private final boolean propagateRequestId;

    public PlatformPropagationDecision decide(String destination) {
        if (!enabled || trusted == null || !trusted.isTrusted(destination)) {
            return PlatformPropagationDecision.DENY_ALL;
        }

        return new PlatformPropagationDecision(propagateForceTrace, propagateQaTrace, propagateRequestId);
    }
}
