package space.br1440.platform.tracing.core.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.manual.DefaultTraceContextView;
import space.br1440.platform.tracing.core.manual.NoOpSpanHandle;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * No-op {@link TracingImplementation} for disabled/unavailable tracing (Slice 2).
 */
public final class NoOpTracingImplementation implements TracingImplementation {

    private final TracingState state;
    private final TraceContextView traceContextView;

    private NoOpTracingImplementation(@Nonnull TracingState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.traceContextView = new DefaultTraceContextView(this::currentTraceId, this::currentSpanId);
    }

    public static NoOpTracingImplementation disabledByConfiguration(@Nonnull String reason) {
        return new NoOpTracingImplementation(ImmutableTracingState.of(
                TracingMode.DISABLED_BY_CONFIGURATION,
                Optional.of(reason),
                Map.of()));
    }

    public static NoOpTracingImplementation unavailable(@Nonnull String reason) {
        return new NoOpTracingImplementation(ImmutableTracingState.of(
                TracingMode.UNAVAILABLE,
                Optional.of(reason),
                Map.of()));
    }

    public static NoOpTracingImplementation noop() {
        return new NoOpTracingImplementation(ImmutableTracingState.of(
                TracingMode.NOOP,
                Optional.empty(),
                Map.of()));
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    @Nonnull
    public TraceContextView currentTraceContext() {
        return traceContextView;
    }

    @Override
    public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        Objects.requireNonNull(span, "span");
    }

    @Override
    @Nonnull
    public TracingState state() {
        return state;
    }

    @Nonnull
    Optional<String> currentTraceId() {
        return Optional.empty();
    }

    @Nonnull
    Optional<String> currentSpanId() {
        return Optional.empty();
    }
}
