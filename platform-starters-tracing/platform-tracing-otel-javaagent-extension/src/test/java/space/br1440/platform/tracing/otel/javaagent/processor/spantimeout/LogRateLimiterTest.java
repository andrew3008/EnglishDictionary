package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class LogRateLimiterTest {

    private static final long INTERVAL = 1_000L;

    @Test
    void firstCallAcquiresRegardlessOfClockValue() {
        // given
        LogRateLimiter limiter = new LogRateLimiter(INTERVAL, () -> Long.MIN_VALUE + 1L);

        // when/then
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void deniesUntilIntervalElapsesAndAcquiresExactlyAtBoundary() {
        // given
        AtomicLong clock = new AtomicLong();
        LogRateLimiter limiter = new LogRateLimiter(INTERVAL, clock::get);
        assertThat(limiter.tryAcquire()).isTrue();

        // when
        clock.set(INTERVAL - 1L);
        boolean beforeBoundary = limiter.tryAcquire();
        clock.set(INTERVAL);
        boolean atBoundary = limiter.tryAcquire();
        boolean immediatelyAfter = limiter.tryAcquire();

        // then
        assertThat(beforeBoundary).isFalse();
        assertThat(atBoundary).isTrue();
        assertThat(immediatelyAfter).isFalse();
    }

    @Test
    void windowIsAnchoredToTheLastAcquiredSlotNotToDeniedAttempts() {
        // given
        AtomicLong clock = new AtomicLong();
        LogRateLimiter limiter = new LogRateLimiter(INTERVAL, clock::get);
        assertThat(limiter.tryAcquire()).isTrue();

        // when
        clock.set(INTERVAL / 2);
        assertThat(limiter.tryAcquire()).isFalse();
        clock.set(INTERVAL);

        // then
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void survivesMonotonicClockWrapAround() {
        // given
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10L);
        LogRateLimiter limiter = new LogRateLimiter(INTERVAL, clock::get);
        assertThat(limiter.tryAcquire()).isTrue();

        // when
        clock.set(Long.MIN_VALUE + INTERVAL);

        // then
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void rejectsNonPositiveInterval() {
        // when/then
        assertThatThrownBy(() -> new LogRateLimiter(0L, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("intervalNanos must be greater than zero");
    }
}
