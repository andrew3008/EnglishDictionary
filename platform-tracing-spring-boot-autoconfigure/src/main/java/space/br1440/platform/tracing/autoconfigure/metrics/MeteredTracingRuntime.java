package space.br1440.platform.tracing.autoconfigure.metrics;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.builder.ActiveTraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.runtime.DelegatingTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingState;

import java.util.Objects;

/**
 * Slice 6: Micrometer decorator for {@link TracingRuntime}.
 * <p>
 * Delegates all span creation to the wrapped implementation and increments self-metrics only.
 * Must not create spans directly or decorate the public {@code TraceOperations} facade.
 */
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
        return new MeteredSpanHandle(delegate.startSpan(spec), metrics);
    }

    @Override
    @Nonnull
    public ActiveTraceContextView currentTraceContext() {
        return delegate.currentTraceContext();
    }

    /**
     * Delegates exception recording to the wrapped implementation without incrementing
     * {@code exceptionsRecorded}.
     * <p>
     * Metric increment for exceptions lives exclusively in {@link MeteredSpanHandle#recordException}
     * to avoid double-counting when a {@link MeteredSpanHandle} is later passed back into this SPI
     * two-arg path (remediation B01).
     */
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
