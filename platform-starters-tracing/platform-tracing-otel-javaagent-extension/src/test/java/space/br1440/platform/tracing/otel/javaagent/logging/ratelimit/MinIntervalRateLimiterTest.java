package space.br1440.platform.tracing.otel.javaagent.logging.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class MinIntervalRateLimiterTest {

    private static final long INTERVAL = 1_000L;

    @Test
    void firstCallAcquiresAtEveryClockValue() {
        // given
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(INTERVAL, () -> Long.MIN_VALUE);

        // when
        boolean acquired = limiter.tryAcquire();

        // then
        assertThat(acquired).isTrue();
    }

    @Test
    void deniesOneNanosecondBeforeBoundaryAndAcquiresAtBoundary() {
        // given
        AtomicLong clock = new AtomicLong();
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(INTERVAL, clock::get);
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
    void deniedAttemptDoesNotMoveBoundary() {
        // given
        AtomicLong clock = new AtomicLong();
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(INTERVAL, clock::get);
        assertThat(limiter.tryAcquire()).isTrue();

        // when
        clock.set(INTERVAL / 2L);
        boolean denied = limiter.tryAcquire();
        clock.set(INTERVAL);
        boolean acquired = limiter.tryAcquire();

        // then
        assertThat(denied).isFalse();
        assertThat(acquired).isTrue();
    }

    @Test
    void preservesExactBoundaryAcrossMonotonicClockWrapAround() {
        // given
        long acquiredAt = Long.MAX_VALUE - (INTERVAL / 2L);
        long nextBoundary = acquiredAt + INTERVAL;
        AtomicLong clock = new AtomicLong(acquiredAt);
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(INTERVAL, clock::get);
        assertThat(limiter.tryAcquire()).isTrue();

        // when
        clock.set(nextBoundary - 1L);
        boolean beforeBoundary = limiter.tryAcquire();
        clock.set(nextBoundary);
        boolean atBoundary = limiter.tryAcquire();

        // then
        assertThat(beforeBoundary).isFalse();
        assertThat(atBoundary).isTrue();
    }

    @Test
    void concurrentEligibleAttemptsReserveExactlyOneSlot() throws Exception {
        // given
        int threadCount = 32;
        AtomicLong clock = new AtomicLong();
        MinIntervalRateLimiter limiter = new MinIntervalRateLimiter(INTERVAL, clock::get);
        assertThat(limiter.tryAcquire()).isTrue();
        clock.set(INTERVAL);

        CyclicBarrier start = new CyclicBarrier(threadCount);
        AtomicInteger acquired = new AtomicInteger();
        List<Callable<Void>> attempts = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            attempts.add(() -> {
                start.await();
                if (limiter.tryAcquire()) {
                    acquired.incrementAndGet();
                }
                return null;
            });
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            // when
            List<Future<Void>> completed = executor.invokeAll(attempts);
            for (Future<Void> attempt : completed) {
                attempt.get();
            }

            // then
            assertThat(acquired).hasValue(1);
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        // when
        // then
        assertThatThrownBy(() -> new MinIntervalRateLimiter(0L, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("minimumIntervalNanos must be greater than zero");
        assertThatThrownBy(() -> new MinIntervalRateLimiter(INTERVAL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nanoTime");
    }

    @Test
    void doesNotAccumulatePermitsDuringIdle() {
        // given
        long intervalNanos = TimeUnit.MINUTES.toNanos(1L);
        AtomicLong clock = new AtomicLong();
        MinIntervalRateLimiter minInterval = new MinIntervalRateLimiter(intervalNanos, clock::get);

        // when
        long initialMinIntervalPermits = countMinIntervalPermits(minInterval);
        clock.addAndGet(intervalNanos * 10L);
        long resumedMinIntervalPermits = countMinIntervalPermits(minInterval);

        // then
        assertThat(initialMinIntervalPermits).isEqualTo(1L);
        assertThat(resumedMinIntervalPermits).isEqualTo(1L);
    }

    private static long countMinIntervalPermits(MinIntervalRateLimiter limiter) {
        long permits = 0L;
        for (int i = 0; i < 6; i++) {
            if (limiter.tryAcquire()) {
                permits++;
            }
        }

        return permits;
    }
}
