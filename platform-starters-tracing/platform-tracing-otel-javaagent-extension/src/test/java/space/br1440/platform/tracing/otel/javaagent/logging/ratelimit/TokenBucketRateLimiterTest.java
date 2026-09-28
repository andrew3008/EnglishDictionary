package space.br1440.platform.tracing.otel.javaagent.logging.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Детерминированные тесты token bucket с виртуальными часами.
 */
class TokenBucketRateLimiterTest {

    @Test
    void debitsUntilExhaustedThenRejects() {
        // given
        AtomicLong clock = new AtomicLong();
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.perInterval(
                3,
                1,
                TimeUnit.MINUTES,
                clock::get
        );

        // when
        boolean first = limiter.trySpend(1.0d);
        boolean second = limiter.trySpend(1.0d);
        boolean third = limiter.trySpend(1.0d);
        boolean fourth = limiter.trySpend(1.0d);

        // then
        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isTrue();
        assertThat(fourth).isFalse();
    }

    @Test
    void refillsContinuouslyUpToCapacity() {
        // given
        AtomicLong clock = new AtomicLong();
        TokenBucketRateLimiter limiter = TokenBucketRateLimiter.perInterval(
                2,
                1,
                TimeUnit.MINUTES,
                clock::get
        );
        assertThat(limiter.trySpend(1.0d)).isTrue();
        assertThat(limiter.trySpend(1.0d)).isTrue();

        // when
        clock.addAndGet(TimeUnit.SECONDS.toNanos(30));

        // then
        assertThat(limiter.trySpend(1.0d)).isTrue();
        assertThat(limiter.trySpend(1.0d)).isFalse();

        // when
        clock.addAndGet(TimeUnit.MINUTES.toNanos(10));

        // then
        assertThat(limiter.trySpend(1.0d)).isTrue();
        assertThat(limiter.trySpend(1.0d)).isTrue();
        assertThat(limiter.trySpend(1.0d)).isFalse();
    }
}
