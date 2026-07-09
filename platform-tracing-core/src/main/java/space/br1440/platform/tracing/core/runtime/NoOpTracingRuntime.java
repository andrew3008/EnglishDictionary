package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.context.DefaultTraceContextView;
import space.br1440.platform.tracing.core.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;
import space.br1440.platform.tracing.core.runtime.state.TracingState;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * No-op {@link TracingRuntime} for disabled/unavailable tracing (Slice 2).
 */
public final class NoOpTracingRuntime implements TracingRuntime {

    private final TracingState state;
    private final TraceContextView traceContextView;

    private NoOpTracingRuntime(@Nonnull TracingState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.traceContextView = new DefaultTraceContextView(this::currentTraceId, this::currentSpanId);
    }

    public static NoOpTracingRuntime disabledByConfiguration(@Nonnull String reason) {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
                TracingMode.DISABLED_BY_CONFIGURATION,
                Optional.of(reason),
                Map.of()));
    }

    public static NoOpTracingRuntime unavailable(@Nonnull String reason) {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
                TracingMode.UNAVAILABLE,
                Optional.of(reason),
                Map.of()));
    }

    public static NoOpTracingRuntime noop() {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
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

    /**
     * No-op runtime has no semconv constraints: returns permissive default {@link AttributePolicy}.
     */
    @Override
    @Nonnull
    public AttributePolicy attributePolicy() {
        return new AttributePolicy();
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
