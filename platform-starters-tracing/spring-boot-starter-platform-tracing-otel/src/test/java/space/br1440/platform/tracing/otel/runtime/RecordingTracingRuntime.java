package space.br1440.platform.tracing.otel.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;

public final class RecordingTracingRuntime implements TracingRuntime {

    private static final SpanAttributeValidator PERMISSIVE = new SpanAttributeValidator();

    private final List<SpanSpec> receivedSpecs = new ArrayList<>();
    private final TracingRuntime identityDelegate = NoOpTracingRuntime.noop();
    private TracingState state = ImmutableTracingState.enabled();

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        receivedSpecs.add(spec);
        return identityDelegate.startSpan(spec);
    }

    @Override
    @Nonnull
    public ActiveTraceContextView currentTraceContext() {
        return identityDelegate.currentTraceContext();
    }

    @Override
    @Nonnull
    public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
        return identityDelegate.openCorrelationScope(correlationId);
    }

    @Override
    @Nonnull
    public CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
        return identityDelegate.openRequestIdentityScope(requestId, forceTrace, qaTrace);
    }

    @Override
    @Nonnull
    public String requireCanonicalCorrelationId(@Nonnull String correlationId) {
        return identityDelegate.requireCanonicalCorrelationId(correlationId);
    }

    @Override
    @Nonnull
    public Optional<String> currentRequestId() {
        return identityDelegate.currentRequestId();
    }

    @Override
    @Nonnull
    public Optional<String> currentCorrelationId() {
        return identityDelegate.currentCorrelationId();
    }

    @Override
    @Nonnull
    public TraceControlBoundaryState currentTraceControlBoundary() {
        return identityDelegate.currentTraceControlBoundary();
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
    public SpanAttributeValidator spanAttributeValidator() {
        return PERMISSIVE;
    }

    public void setState(@Nonnull TracingState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Nonnull
    public List<SpanSpec> receivedSpecs() {
        return List.copyOf(receivedSpecs);
    }
}
