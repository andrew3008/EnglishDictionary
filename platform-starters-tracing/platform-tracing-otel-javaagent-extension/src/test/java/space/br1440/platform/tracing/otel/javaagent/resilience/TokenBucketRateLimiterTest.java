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
        // given
        AtomicLong clock = new AtomicLong(0);
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.perInterval(3, 1, TimeUnit.MINUTES, clock::get);

        // when
        boolean first = limiter.trySpend(1.0);
        boolean second = limiter.trySpend(1.0);
        boolean third = limiter.trySpend(1.0);
        boolean fourth = limiter.trySpend(1.0);

        // then
        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isTrue();
        assertThat(fourth).as("ведро пусто").isFalse();
    }

    @Test
    void refills_over_time() {
        // given
        AtomicLong clock = new AtomicLong(0);
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.perInterval(1, 1, TimeUnit.MINUTES, clock::get);

        assertThat(limiter.trySpend(1.0)).isTrue();
        assertThat(limiter.trySpend(1.0)).isFalse();

        // when
        clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        boolean afterRefill = limiter.trySpend(1.0);

        // then
        assertThat(afterRefill).isTrue();
    }
}
