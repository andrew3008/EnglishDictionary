package space.br1440.platform.tracing.otel.javaagent.logging.ratelimit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class MinIntervalRateLimiter {

    private final long minimumIntervalNanos;
    private final LongSupplier nanoTime;
    private final AtomicReference<Acquisition> lastAcquisition = new AtomicReference<>();

    public MinIntervalRateLimiter(long minimumIntervalNanos, LongSupplier nanoTime) {
        if (minimumIntervalNanos <= 0L) {
            throw new IllegalArgumentException("minimumIntervalNanos must be greater than zero");
        }

        this.minimumIntervalNanos = minimumIntervalNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        Acquisition previous = lastAcquisition.get();
        while (previous == null || elapsedSince(now, previous.nanoTime()) >= minimumIntervalNanos) {
            Acquisition acquired = new Acquisition(now);
            if (lastAcquisition.compareAndSet(previous, acquired)) {
                return true;
            }

            previous = lastAcquisition.get();
        }

        return false;
    }

    private static long elapsedSince(long now, long previous) {
        return now - previous;
    }

    private record Acquisition(long nanoTime) {
    }
}
