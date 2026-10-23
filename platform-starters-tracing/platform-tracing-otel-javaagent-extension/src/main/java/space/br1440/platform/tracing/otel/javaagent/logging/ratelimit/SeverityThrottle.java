package space.br1440.platform.tracing.otel.javaagent.logging.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class SeverityThrottle {

    private static final Acquisition DENIED = new Acquisition(Decision.DENY, 0L);

    public record Acquisition(Decision decision, long suppressedCount) {
    }

    public enum Decision {
        ADMIT,
        ADMIT_WITH_MARKER,
        DENY
    }

    private final int burst;
    private final long intervalNanos;
    private final long capacity;
    private final LongSupplier nanoClock;

    private boolean slowMode;
    private long credit;
    private long lastRefillNanos;
    private long nextSlowAdmitNanos;
    private long suppressed;

    public SeverityThrottle(int burst,
                            Duration interval,
                            LongSupplier nanoClock) {
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be greater than zero");
        }

        Duration requiredInterval = Objects.requireNonNull(interval, "interval");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (requiredInterval.isZero() || requiredInterval.isNegative()) {
            throw new IllegalArgumentException("interval must be greater than zero");
        }

        try {
            this.intervalNanos = requiredInterval.toNanos();
            this.capacity = Math.multiplyExact(this.intervalNanos, burst);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("interval and burst must fit in a long nanosecond capacity", exception);
        }

        this.burst = burst;
        this.credit = capacity;
        this.lastRefillNanos = this.nanoClock.getAsLong();
    }

    public synchronized Acquisition tryAcquire() {
        long now = nanoClock.getAsLong();
        refill(now);

        if (!slowMode) {
            if (credit >= intervalNanos) {
                credit -= intervalNanos;
                return admitted(Decision.ADMIT);
            }

            slowMode = true;
            credit = 0L;
            nextSlowAdmitNanos = now + intervalNanos;
            return admitted(Decision.ADMIT_WITH_MARKER);
        }

        if (credit == capacity) {
            slowMode = false;
            credit -= intervalNanos;
            return admitted(Decision.ADMIT);
        }

        credit = (credit >= intervalNanos) ? credit - intervalNanos : 0L;
        if ((now - nextSlowAdmitNanos) >= 0L) {
            nextSlowAdmitNanos = now + intervalNanos;
            return admitted(Decision.ADMIT);
        }

        if (suppressed < Long.MAX_VALUE) {
            suppressed++;
        }
        return DENIED;
    }

    private Acquisition admitted(Decision decision) {
        long suppressedCount = suppressed;
        suppressed = 0L;
        return new Acquisition(decision, suppressedCount);
    }

    private void refill(long now) {
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0L) {
            return;
        }

        long refill = Math.min(elapsed, intervalNanos) * burst;
        long missing = (capacity - credit);
        credit = (refill >= missing) ? capacity : credit + refill;
        lastRefillNanos = now;
    }
}
