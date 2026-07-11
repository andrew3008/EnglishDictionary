package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.runtime.state.TracingState;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

public interface TracingRuntime {

    @Nonnull
    SpanHandle startSpan(@Nonnull SpanSpec spec);

    @Nonnull
    ActiveTraceContextView currentTraceContext();

    void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable);

    @Nonnull
    TracingState state();

    @Nonnull
    AttributePolicy attributePolicy();

}
