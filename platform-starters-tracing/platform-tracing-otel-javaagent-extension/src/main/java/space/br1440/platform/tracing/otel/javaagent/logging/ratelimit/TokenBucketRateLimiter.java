package space.br1440.platform.tracing.otel.javaagent.logging.ratelimit;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class TokenBucketRateLimiter {

    private final double maxTokens;
    private final double refillRatePerNano;
    private final LongSupplier nanoClock;

    private double availableTokens;
    private long lastRefillNanos;

    TokenBucketRateLimiter(double permitsPerInterval,
                           long intervalNanos,
                           double maxTokens,
                           LongSupplier nanoClock) {
        if (!Double.isFinite(permitsPerInterval)
                || permitsPerInterval <= 0.0d
                || intervalNanos <= 0L
                || !Double.isFinite(maxTokens)
                || maxTokens <= 0.0d) {
            throw new IllegalArgumentException("permitsPerInterval, intervalNanos and maxTokens must be greater than zero");
        }

        double refillRate = permitsPerInterval / (double) intervalNanos;
        if (refillRate == 0.0d) {
            throw new IllegalArgumentException("refill rate must be greater than zero");
        }

        this.maxTokens = maxTokens;
        this.refillRatePerNano = refillRate;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.availableTokens = maxTokens;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    static TokenBucketRateLimiter perInterval(double permits,
                                              long interval,
                                              TimeUnit unit,
                                              LongSupplier nanoClock) {
        Objects.requireNonNull(unit, "unit");
        return new TokenBucketRateLimiter(permits, unit.toNanos(interval), permits, nanoClock);
    }

    @SuppressWarnings("SameParameterValue")
    synchronized boolean trySpend(double cost) {
        if (!Double.isFinite(cost) || cost <= 0.0d) {
            throw new IllegalArgumentException("cost must be greater than zero");
        }

        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            availableTokens = Math.min(maxTokens, availableTokens + elapsed * refillRatePerNano);
            lastRefillNanos = now;
        }

        if (availableTokens >= cost) {
            availableTokens -= cost;
            return true;
        }

        return false;
    }
}
