package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class LogRateLimiter {

    private static final long NEVER_ACQUIRED_NANOS = Long.MIN_VALUE;

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong lastAcquiredNanos = new AtomicLong(NEVER_ACQUIRED_NANOS);

    LogRateLimiter(long intervalNanos, LongSupplier nanoTime) {
        if (intervalNanos <= 0L) {
            throw new IllegalArgumentException("intervalNanos must be greater than zero");
        }

        this.intervalNanos = intervalNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        long previous = lastAcquiredNanos.get();
        if ((previous != NEVER_ACQUIRED_NANOS) && ((now - previous) < intervalNanos)) {
            return false;
        }

        return lastAcquiredNanos.compareAndSet(previous, now);
    }
}
