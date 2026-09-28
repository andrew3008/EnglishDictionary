package space.br1440.platform.tracing.otel.javaagent.logging.ratelimit;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class SeverityThrottle {

    public record Acquisition(Decision decision, long suppressedCount) {
    }

    public enum Decision {
        ADMIT,
        ADMIT_WITH_MARKER,
        DENY
    }

    private enum Phase {
        FAST,
        TRANSITIONING,
        SLOW
    }

    private final TokenBucketRateLimiter fastGate;
    private final MinIntervalRateLimiter slowGate;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.FAST);
    private final AtomicLong suppressed = new AtomicLong();

    public SeverityThrottle(double fastPermits,
                            double slowPermits,
                            long interval,
                            TimeUnit unit,
                            LongSupplier nanoClock) {
        if (Double.compare(slowPermits, 1.0d) != 0) {
            throw new IllegalArgumentException("slowPermits must be exactly 1");
        }

        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(nanoClock, "nanoClock");
        long intervalNanos = unit.toNanos(interval);
        this.fastGate = TokenBucketRateLimiter.perInterval(fastPermits, interval, unit, nanoClock);
        this.slowGate = new MinIntervalRateLimiter(intervalNanos, nanoClock);
    }

    public Acquisition tryAcquire() {
        for (; ; ) {
            Phase current = phase.get();
            if (current == Phase.SLOW) {
                return slowGate.tryAcquire() ? admitted(Decision.ADMIT) : denied();
            }

            if (current == Phase.TRANSITIONING) {
                Thread.onSpinWait();
                continue;
            }

            if (fastGate.trySpend(1.0d)) {
                return admitted(Decision.ADMIT);
            }

            if (!phase.compareAndSet(Phase.FAST, Phase.TRANSITIONING)) {
                continue;
            }

            try {
                return slowGate.tryAcquire() ? admitted(Decision.ADMIT_WITH_MARKER) : denied();
            } finally {
                phase.set(Phase.SLOW);
            }
        }
    }

    public boolean isThrottled() {
        return (phase.get() != Phase.FAST);
    }

    public long drainSuppressed() {
        return suppressed.getAndSet(0L);
    }

    private Acquisition admitted(Decision decision) {
        return new Acquisition(decision, drainSuppressed());
    }

    private Acquisition denied() {
        suppressed.incrementAndGet();
        return new Acquisition(Decision.DENY, 0L);
    }
}
