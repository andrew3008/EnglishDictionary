package space.br1440.platform.tracing.otel.javaagent.resilience;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class TokenBucketRateLimiter {

    private final double maxTokens;
    private final double refillRatePerNano;
    private final LongSupplier nanoClock;

    private double availableTokens;
    private long lastRefillNanos;

    TokenBucketRateLimiter(double permitsPerInterval, long intervalNanos, double maxTokens, LongSupplier nanoClock) {
        if (permitsPerInterval <= 0 || intervalNanos <= 0 || maxTokens <= 0) {
            throw new IllegalArgumentException("permitsPerInterval, intervalNanos и maxTokens должны быть > 0");
        }

        this.maxTokens = maxTokens;
        this.refillRatePerNano = permitsPerInterval / (double) intervalNanos;
        this.nanoClock = nanoClock;
        this.availableTokens = maxTokens;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    static TokenBucketRateLimiter perInterval(double permits, long interval, TimeUnit unit, LongSupplier nanoClock) {
        return new TokenBucketRateLimiter(permits, unit.toNanos(interval), permits, nanoClock);
    }

    @SuppressWarnings("SameParameterValue")
    synchronized boolean trySpend(double cost) {
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
