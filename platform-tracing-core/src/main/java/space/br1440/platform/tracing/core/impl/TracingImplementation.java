package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;

/**
 * Internal single span-creation boundary for platform manual tracing (Slice 2).
 * <p>
 * Not application-facing API. Fully abstract SPI: no default methods, no behavioral static helpers.
 */
public interface TracingImplementation {

    @Nonnull
    SpanHandle startSpan(@Nonnull SpanSpec spec);

    @Nonnull
    TraceContextView currentTraceContext();

    void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable);

    @Nonnull
    TracingState state();
}
