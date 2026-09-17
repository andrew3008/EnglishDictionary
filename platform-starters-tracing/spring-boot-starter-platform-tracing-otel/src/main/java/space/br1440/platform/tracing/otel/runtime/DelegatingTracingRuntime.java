package space.br1440.platform.tracing.otel.runtime;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;

public interface DelegatingTracingRuntime extends TracingRuntime {

    @Nonnull
    TracingRuntime delegate();

    @Override
    @Nonnull
    default SpanAttributeValidator spanAttributeValidator() {
        return delegate().spanAttributeValidator();
    }

    @Override
    @Nonnull
    default CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
        return delegate().openRequestIdentityScope(requestId, forceTrace, qaTrace);
    }

    @Override
    @Nonnull
    default TraceControlBoundaryState currentTraceControlBoundary() {
        return delegate().currentTraceControlBoundary();
    }
}
