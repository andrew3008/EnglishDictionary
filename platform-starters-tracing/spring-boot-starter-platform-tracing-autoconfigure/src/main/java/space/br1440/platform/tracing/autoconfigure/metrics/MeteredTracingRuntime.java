package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.Objects;
import java.util.Optional;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.DelegatingTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

public final class MeteredTracingRuntime implements DelegatingTracingRuntime {

    private final TracingRuntime delegate;
    private final PlatformTracingMetrics metrics;

    public MeteredTracingRuntime(@Nonnull TracingRuntime delegate,
                                 @Nonnull PlatformTracingMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        metrics.incrementSpansStarted(spec.category());
        SpanHandle started = delegate.startSpan(spec);
        if (started instanceof DatabaseSpanHandle databaseHandle) {
            return new MeteredDatabaseSpanHandle(databaseHandle, metrics);
        }

        return new MeteredSpanHandle(started, metrics);
    }

    @Override
    @Nonnull
    public ActiveTraceContextView currentTraceContext() {
        return delegate.currentTraceContext();
    }

    @Override
    @Nonnull
    public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
        return delegate.openCorrelationScope(correlationId);
    }

    @Override
    @Nonnull
    public String requireCanonicalCorrelationId(@Nonnull String correlationId) {
        return delegate.requireCanonicalCorrelationId(correlationId);
    }

    @Override
    @Nonnull
    public Optional<String> currentRequestId() {
        return delegate.currentRequestId();
    }

    @Override
    @Nonnull
    public Optional<String> currentCorrelationId() {
        return delegate.currentCorrelationId();
    }

    @Override
    public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        delegate.recordException(span, throwable);
    }

    @Override
    @Nonnull
    public TracingState state() {
        return delegate.state();
    }

    @Override
    @Nonnull
    public TracingRuntime delegate() {
        return delegate;
    }
}
