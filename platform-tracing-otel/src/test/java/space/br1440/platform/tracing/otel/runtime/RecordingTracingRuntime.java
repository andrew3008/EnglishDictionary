package space.br1440.platform.tracing.otel.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Test double recording {@link SpanSpec} passed to {@link #startSpan(SpanSpec)}.
 */
public final class RecordingTracingRuntime implements TracingRuntime {

    /** Permissive default policy shared across recording instances; never mutated (validation is bypassed in tests). */
    private static final AttributePolicy PERMISSIVE = new AttributePolicy();

    private final List<SpanSpec> receivedSpecs = new ArrayList<>();
    private final TracingRuntime identityDelegate = NoOpTracingRuntime.noop();
    private TracingState state = ImmutableTracingState.enabled();

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        receivedSpecs.add(spec);
        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    @Nonnull
    public ActiveTraceContextView currentTraceContext() {
        return identityDelegate.currentTraceContext();
    }

    @Override
    public CorrelationScope openCorrelationScope(String correlationId) {
        return identityDelegate.openCorrelationScope(correlationId);
    }

    @Override
    public CorrelationScope openRequestIdentityScope(String requestId) {
        return identityDelegate.openRequestIdentityScope(requestId);
    }

    @Override
    public String requireCanonicalCorrelationId(String correlationId) {
        return identityDelegate.requireCanonicalCorrelationId(correlationId);
    }

    @Override
    public Optional<String> currentRequestId() {
        return identityDelegate.currentRequestId();
    }

    @Override
    public Optional<String> currentCorrelationId() {
        return identityDelegate.currentCorrelationId();
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

    @Override
    @Nonnull
    public AttributePolicy attributePolicy() {
        return PERMISSIVE;
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
