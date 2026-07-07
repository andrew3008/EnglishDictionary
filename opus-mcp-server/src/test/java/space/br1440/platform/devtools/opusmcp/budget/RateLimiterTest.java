package space.br1440.platform.devtools.opusmcp.budget;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void disabledLimiterAlwaysAllows() {
        RateLimiter limiter = new RateLimiter(0);
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
    }

    @Test
    void blocksWhenExceedingPermitsInWindow() {
        AtomicLong now = new AtomicLong(10_000L);
        RateLimiter limiter = new RateLimiter(2, now::get);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void permitsRecoverAfterWindowSlidesPast() {
        AtomicLong now = new AtomicLong(10_000L);
        RateLimiter limiter = new RateLimiter(1, now::get);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        now.addAndGet(61_000L);
        assertThat(limiter.tryAcquire()).isTrue();
    }
}
