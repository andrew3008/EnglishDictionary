package space.br1440.platform.tracing.core.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.runtime.NoOpSpanHandle;
import space.br1440.platform.tracing.core.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.core.runtime.state.TracingState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test double recording {@link SpanSpec} passed to {@link #startSpan(SpanSpec)}.
 */
public final class RecordingTracingRuntime implements TracingRuntime {

    private final List<SpanSpec> receivedSpecs = new ArrayList<>();
    private TracingState state = ImmutableTracingState.enabled();

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        receivedSpecs.add(spec);
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    @Nonnull
    public TraceContextView currentTraceContext() {
        return NoOpTracingRuntime.noop().currentTraceContext();
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

    public void setState(@Nonnull TracingState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Nonnull
    public List<SpanSpec> receivedSpecs() {
        return List.copyOf(receivedSpecs);
    }

    public void reset() {
        receivedSpecs.clear();
    }
}
