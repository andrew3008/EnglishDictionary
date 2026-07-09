package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.runtime.state.TracingState;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

/**
 * Internal single span-creation boundary for platform manual tracing (Slice 2).
 * <p>
 * Not application-facing API. Fully abstract SPI: no default methods, no behavioral static helpers.
 * <p>
 * {@link #attributePolicy()} exposes the semconv policy governing attribute validation at
 * creation-time. Every runtime must declare its policy; no-op runtimes return a permissive
 * default. This eliminates the need for {@code instanceof OtelTracingRuntime} in facade wiring.
 */
public interface TracingRuntime {

    @Nonnull
    SpanHandle startSpan(@Nonnull SpanSpec spec);

    @Nonnull
    TraceContextView currentTraceContext();

    void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable);

    @Nonnull
    TracingState state();

    /**
     * Returns the {@link AttributePolicy} governing attribute validation for spans created by
     * this runtime. No-op and delegating runtimes must provide a valid (possibly permissive)
     * policy; never {@code null}.
     */
    @Nonnull
    AttributePolicy attributePolicy();
}
