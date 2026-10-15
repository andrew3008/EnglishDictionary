package space.br1440.platform.tracing.runtime.propagation.control;

import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

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

        try {
            Optional<OutboundPropagationHeaders.Header> forceTrace =
                    decision.propagateForceTrace() && control != null && control.forceTrace()
                            ? header(PlatformHeaders.X_TRACE_ON, "on")
                            : Optional.empty();

            Optional<OutboundPropagationHeaders.Header> qaTrace =
                    decision.propagateQaTrace() && control != null && control.qaTrace()
                            ? header(PlatformHeaders.X_QA_TRACE, "1")
                            : Optional.empty();

            String requestId = identitySource.currentRequestId();
            Optional<OutboundPropagationHeaders.Header> requestIdHeaderValue =
                    decision.propagateRequestId() && requestId != null
                            ? header(PlatformHeaders.X_REQUEST_ID, requestId)
                            : Optional.empty();

            String correlationId = identitySource.currentCorrelationId();
            Optional<OutboundPropagationHeaders.Header> correlationIdHeaderValue =
                    decision.propagateCorrelationId() && correlationId != null
                            ? header(PlatformHeaders.X_CORRELATION_ID, correlationId)
                            : Optional.empty();

            if (forceTrace.isEmpty() && qaTrace.isEmpty() && requestIdHeaderValue.isEmpty() && correlationIdHeaderValue.isEmpty()) {
                return OutboundPropagationHeaders.EMPTY;
            }

            return new OutboundPropagationHeaders(forceTrace, qaTrace, requestIdHeaderValue, correlationIdHeaderValue);
        } catch (RuntimeException ignored) {
            return OutboundPropagationHeaders.EMPTY;
        }
    }

    private static Optional<OutboundPropagationHeaders.Header> header(String name, String value) {
        return Optional.of(new OutboundPropagationHeaders.Header(name, value));
    }

    private static boolean isDenied(OutboundPropagationDecision decision) {
        return decision == null
                || (!decision.propagateForceTrace()
                && !decision.propagateQaTrace()
                && !decision.propagateRequestId()
                && !decision.propagateCorrelationId());
    }
}
