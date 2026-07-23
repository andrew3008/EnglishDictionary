package space.br1440.platform.tracing.otel.runtime;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.context.DefaultActiveTraceContextView;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NoOpTracingRuntime implements TracingRuntime {

    private static final AttributePolicy PERMISSIVE = new AttributePolicy();

    private final TracingState state;
    private final ThreadLocalIdentityStorage identityStorage = new ThreadLocalIdentityStorage();
    private final ActiveTraceContextView traceContextView;

    private NoOpTracingRuntime(@Nonnull TracingState state) {
        this.state = Objects.requireNonNull(state, "state");
        this.traceContextView = new DefaultActiveTraceContextView(
                this::currentTraceId,
                this::currentSpanId,
                this::currentRequestId,
                this::currentCorrelationId);
    }

    public static NoOpTracingRuntime disabledByConfiguration(@Nonnull String reason) {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
                TracingMode.DISABLED_BY_CONFIGURATION,
                reason,
                Map.of()));
    }

    public static NoOpTracingRuntime unavailable(@Nonnull String reason) {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
                TracingMode.UNAVAILABLE,
                reason,
                Map.of()));
    }

    public static NoOpTracingRuntime noop() {
        return new NoOpTracingRuntime(ImmutableTracingState.of(
                TracingMode.NOOP,
                null,
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
    public CorrelationScope openRequestIdentityScope(@Nonnull String requestId) {
        return identityStorage.openRequestScope(requestId);
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

    @Nonnull
    Optional<String> currentTraceId() {
        return Optional.empty();
    }

    @Nonnull
    Optional<String> currentSpanId() {
        return Optional.empty();
    }
}
