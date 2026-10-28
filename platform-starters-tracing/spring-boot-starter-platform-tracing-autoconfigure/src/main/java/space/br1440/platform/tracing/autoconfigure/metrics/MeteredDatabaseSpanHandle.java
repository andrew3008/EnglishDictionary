package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.Objects;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;

final class MeteredDatabaseSpanHandle implements DatabaseSpanHandle {

    private final DatabaseSpanHandle delegate;
    private final PlatformTracingMetrics metrics;

    MeteredDatabaseSpanHandle(@Nonnull DatabaseSpanHandle delegate, @Nonnull PlatformTracingMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void responseStatusCode(@Nullable String code) {
        delegate.responseStatusCode(code);
    }

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
