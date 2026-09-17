package space.br1440.platform.tracing.otel.runtime;

import java.util.Optional;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;

public interface TracingRuntime {

    @Nonnull
    SpanHandle startSpan(@Nonnull SpanSpec spec);

    @Nonnull
    ActiveTraceContextView currentTraceContext();

    @Nonnull
    CorrelationScope openCorrelationScope(@Nonnull String correlationId);

    @Nonnull
    CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace);

    @Nonnull
    default CorrelationScope openRequestIdentityScope(@Nonnull String requestId) {
        return openRequestIdentityScope(requestId, false, false);
    }

    @Nonnull
    String requireCanonicalCorrelationId(@Nonnull String correlationId);

    @Nonnull
    Optional<String> currentRequestId();

    @Nonnull
    Optional<String> currentCorrelationId();

    @Nonnull
    TraceControlBoundaryState currentTraceControlBoundary();

    void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable);

    @Nonnull
    TracingState state();

    @Nonnull
    SpanAttributeValidator spanAttributeValidator();

}
