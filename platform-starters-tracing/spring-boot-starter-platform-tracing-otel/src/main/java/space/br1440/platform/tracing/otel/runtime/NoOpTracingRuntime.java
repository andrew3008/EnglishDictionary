package space.br1440.platform.tracing.otel.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.context.DefaultActiveTraceContextView;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

public final class NoOpTracingRuntime implements TracingRuntime {

    private static final SpanAttributeValidator PERMISSIVE = new SpanAttributeValidator();

    private final TracingState state;
    private final ThreadLocalIdentityStorage identityStorage = new ThreadLocalIdentityStorage();
    private final ActiveTraceContextView traceContextView;

    private NoOpTracingRuntime(@Nonnull TracingState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.traceContextView = new DefaultActiveTraceContextView(
                this::currentTraceId,
                this::currentSpanId,
                this::currentRequestId,
                this::currentCorrelationId,
                this::currentTraceparent
        );
    }

    public static NoOpTracingRuntime disabledByConfiguration(@Nonnull String reason) {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
                TracingRuntimeMode.DISABLED_BY_CONFIGURATION,
                reason,
                Map.of()));
    }

    public static NoOpTracingRuntime noop() {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
                TracingRuntimeMode.NOOP,
                null,
                Map.of()));
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.category() == SpanCategory.DATABASE) {
            return NoOpDatabaseSpanHandle.INSTANCE;
        }

        return NoOpSpanHandle.INSTANCE;
    }

    @Override
    @Nonnull
    public ActiveTraceContextView currentTraceContext() {
        return traceContextView;
    }

    @Override
    @Nonnull
    public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
        return identityStorage.openCorrelationScope(correlationId);
    }

    @Override
    @Nonnull
    public CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
        return identityStorage.openRequestScope(requestId, forceTrace, qaTrace);
    }

    @Override
    @Nonnull
    public String requireCanonicalCorrelationId(@Nonnull String correlationId) {
        return identityStorage.requireCanonicalCorrelationId(correlationId);
    }

    @Override
    @Nonnull
    public Optional<String> currentRequestId() {
        return identityStorage.requestId();
    }

    @Override
    @Nonnull
    public Optional<String> currentCorrelationId() {
        return identityStorage.correlationId();
    }

    @Override
    @Nonnull
    public TraceControlBoundaryState currentTraceControlBoundary() {
        return identityStorage.traceControlBoundary();
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

    @Nonnull
    Optional<String> currentTraceId() {
        return Optional.empty();
    }

    @Nonnull
    Optional<String> currentSpanId() {
        return Optional.empty();
    }

    @Nonnull
    Optional<String> currentTraceparent() {
        return Optional.empty();
    }
}
