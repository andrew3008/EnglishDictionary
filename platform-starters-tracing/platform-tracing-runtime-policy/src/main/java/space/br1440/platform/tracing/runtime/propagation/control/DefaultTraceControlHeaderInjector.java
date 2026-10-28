package space.br1440.platform.tracing.runtime.propagation.control;

import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.runtime.propagation.CorrelationIdSupport;
import space.br1440.platform.tracing.runtime.propagation.RequestIdSupport;

public final class DefaultTraceControlHeaderInjector implements PlatformOutboundPropagation {

    private final OutboundApplicationIdentitySource identitySource;

    public DefaultTraceControlHeaderInjector(OutboundApplicationIdentitySource identitySource) {
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
    }

    @Nonnull
    @Override
    public OutboundPropagationHeaders resolve(@Nullable OutboundPropagationDecision decision, @Nullable InboundTraceControl control) {
        if (isDenied(decision)) {
            return OutboundPropagationHeaders.EMPTY;
        }

        Optional<String> forceTrace = decision.propagateForceTrace() && control != null && control.forceTrace()
                ? Optional.of("on")
                : Optional.empty();

        Optional<String> qaTrace = decision.propagateQaTrace() && control != null && control.qaTrace()
                ? Optional.of("1")
                : Optional.empty();

        Optional<String> requestId = decision.propagateRequestId()
                ? Optional.ofNullable(RequestIdSupport.sanitizeOrNull(identitySource.currentRequestId()))
                : Optional.empty();

        Optional<String> correlationId = decision.propagateCorrelationId()
                ? Optional.ofNullable(CorrelationIdSupport.sanitizeOrNull(identitySource.currentCorrelationId()))
                : Optional.empty();

        return new OutboundPropagationHeaders(forceTrace, qaTrace, requestId, correlationId);
    }

    private static boolean isDenied(OutboundPropagationDecision decision) {
        return decision == null
                || (!decision.propagateForceTrace()
                && !decision.propagateQaTrace()
                && !decision.propagateRequestId()
                && !decision.propagateCorrelationId());
    }
}
