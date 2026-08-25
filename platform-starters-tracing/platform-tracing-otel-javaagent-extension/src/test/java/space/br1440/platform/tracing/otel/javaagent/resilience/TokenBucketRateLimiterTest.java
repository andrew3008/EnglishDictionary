package space.br1440.platform.tracing.otel.javaagent.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Детерминированные тесты token-bucket с виртуальными часами: списание токенов и пополнение.
 */
class TokenBucketRateLimiterTest {

    @Test
    void debits_until_exhausted_then_rejects() {
        AtomicLong clock = new AtomicLong(0);
        // 3 токена за минуту, ёмкость 3, время не идёт.
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.perInterval(3, 1, TimeUnit.MINUTES, clock::get);

        assertThat(limiter.trySpend(1.0)).isTrue();
        assertThat(limiter.trySpend(1.0)).isTrue();
        assertThat(limiter.trySpend(1.0)).isTrue();
        assertThat(limiter.trySpend(1.0)).as("ведро пусто").isFalse();
    }

    @Test
    void refills_over_time() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.perInterval(1, 1, TimeUnit.MINUTES, clock::get);

        assertThat(limiter.trySpend(1.0)).isTrue();
        assertThat(limiter.trySpend(1.0)).isFalse();

        // Прошла минута — пополнился один токен.
        clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        assertThat(limiter.trySpend(1.0)).isTrue();
    }
}
