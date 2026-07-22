package space.br1440.platform.tracing.otel.javaagent.safety;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Лёгкий потокобезопасный token-bucket rate limiter (без аллокаций на горячем пути).
 * <p>
 * Перенимает идею {@code io.opentelemetry.sdk.common.internal.RateLimiter}: ведро ёмкостью
 * {@code maxTokens} пополняется со скоростью {@code refillRatePerNano} токенов в наносекунду;
 * {@link #trySpend(double)} списывает токены, если их достаточно. Используется
 * {@link RateLimitedLogger} для ограничения частоты записи в лог.
 *
 * <p><b>Потокобезопасность:</b> состояние ({@link #availableTokens}, {@link #lastRefillNanos})
 * защищено {@code synchronized} — критическая секция занимает доли наносекунды (арифметика),
 * блокировок ввода-вывода нет.
 */
final class TokenBucketRateLimiter {

    private final double maxTokens;
    private final double refillRatePerNano;
    private final LongSupplier nanoClock;

    private double availableTokens;
    private long lastRefillNanos;

    /**
     * @param permitsPerInterval сколько токенов начисляется за {@code intervalNanos}
     * @param intervalNanos      длительность интервала пополнения в наносекундах
     * @param maxTokens          ёмкость ведра (максимум накопленных токенов)
     * @param nanoClock          источник монотонного времени (для тестов — виртуальные часы)
     */
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

    /** Фабрика: {@code permits} токенов за интервал; ёмкость ведра == {@code permits}. */
    static TokenBucketRateLimiter perInterval(double permits, long interval, TimeUnit unit, LongSupplier nanoClock) {
        return new TokenBucketRateLimiter(permits, unit.toNanos(interval), permits, nanoClock);
    }

    /**
     * Пытается списать {@code cost} токенов.
     *
     * @return {@code true}, если токенов хватило (списано); {@code false} — лимит исчерпан
     */
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
