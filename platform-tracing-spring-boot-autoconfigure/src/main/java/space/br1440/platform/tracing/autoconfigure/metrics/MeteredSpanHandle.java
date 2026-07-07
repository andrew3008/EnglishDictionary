package space.br1440.platform.tracing.autoconfigure.metrics;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;

import java.util.Objects;

final class MeteredSpanHandle implements SpanHandle {

    private final SpanHandle delegate;
    private final PlatformTracingMetrics metrics;

    MeteredSpanHandle(@Nonnull SpanHandle delegate, @Nonnull PlatformTracingMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Single source of truth for {@code exceptionsRecorded} metric increment.
     * Do not duplicate this increment in {@link MeteredTracingImplementation#recordException}.
     */
    @Override
    public void recordException(@Nullable Throwable throwable) {
        if (throwable != null) {
            metrics.incrementExceptionsRecorded();
        }
        delegate.recordException(throwable);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
