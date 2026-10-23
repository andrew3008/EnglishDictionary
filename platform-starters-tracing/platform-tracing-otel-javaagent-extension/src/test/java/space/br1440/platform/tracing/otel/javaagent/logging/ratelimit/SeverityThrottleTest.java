package space.br1440.platform.tracing.otel.javaagent.logging.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SeverityThrottleTest {

    private static final int DEFAULT_BURST = 5;
    private static final int CONCURRENT_ATTEMPTS = 16;
    private static final Duration INTERVAL = Duration.ofMinutes(1L);
    private static final long INTERVAL_NANOS = INTERVAL.toNanos();

    @Test
    void exhaustsFastBurstThenEmitsOneTransitionMarker() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);

        // when
        List<SeverityThrottle.Acquisition> acquisitions = acquire(throttle, DEFAULT_BURST + 2);

        // then
        assertThat(acquisitions)
                .extracting(SeverityThrottle.Acquisition::decision)
                .containsExactly(
                        SeverityThrottle.Decision.ADMIT,
                        SeverityThrottle.Decision.ADMIT,
                        SeverityThrottle.Decision.ADMIT,
                        SeverityThrottle.Decision.ADMIT,
                        SeverityThrottle.Decision.ADMIT,
                        SeverityThrottle.Decision.ADMIT_WITH_MARKER,
                        SeverityThrottle.Decision.DENY
                );
    }

    @Test
    void partialRefillRestoresOneFastAdmission() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        assertThat(acquire(throttle, DEFAULT_BURST))
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT);

        // when
        clock.set(INTERVAL_NANOS / DEFAULT_BURST);
        SeverityThrottle.Acquisition refilled = throttle.tryAcquire();
        SeverityThrottle.Acquisition exhausted = throttle.tryAcquire();

        // then
        assertThat(refilled.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
        assertThat(exhausted.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT_WITH_MARKER);
    }

    @Test
    void fastRefillSurvivesNanoTimeWraparound() {
        // given
        long initialTime = Long.MAX_VALUE - 5L;
        AtomicLong clock = new AtomicLong(initialTime);
        SeverityThrottle throttle = new SeverityThrottle(2, Duration.ofNanos(10L), clock::get);
        assertThat(acquire(throttle, 2))
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT);

        // when
        clock.set(Long.MIN_VALUE);
        SeverityThrottle.Acquisition refilled = throttle.tryAcquire();
        SeverityThrottle.Acquisition exhausted = throttle.tryAcquire();

        // then
        assertThat(refilled.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
        assertThat(exhausted.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT_WITH_MARKER);
    }

    @Test
    void slowAttemptsEveryTwelveSecondsAdmitOncePerInterval() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);

        // when
        List<SeverityThrottle.Acquisition> acquisitions = new ArrayList<>();
        for (int second = 12; second <= 120; second += 12) {
            clock.set(Duration.ofSeconds(second).toNanos());
            acquisitions.add(throttle.tryAcquire());
        }

        // then
        assertThat(acquisitions)
                .extracting(SeverityThrottle.Acquisition::decision)
                .containsExactly(
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.ADMIT,
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.DENY,
                        SeverityThrottle.Decision.ADMIT
                );
        assertThat(acquisitions.get(4).suppressedCount()).isEqualTo(4L);
        assertThat(acquisitions.get(9).suppressedCount()).isEqualTo(4L);
    }

    @Test
    void attemptOneNanosecondBeforeHealBoundaryRemainsSlow() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);

        // when
        clock.set(INTERVAL_NANOS - 1L);
        SeverityThrottle.Acquisition acquisition = throttle.tryAcquire();

        // then
        assertThat(acquisition.decision()).isEqualTo(SeverityThrottle.Decision.DENY);
    }

    @Test
    void attemptAtHealBoundaryReturnsToFastWithSuppressedCount() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);
        assertThat(acquire(throttle, 3))
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.DENY);

        // when
        clock.set(INTERVAL_NANOS);
        SeverityThrottle.Acquisition healed = throttle.tryAcquire();
        List<SeverityThrottle.Acquisition> remainingBurst = acquire(throttle, DEFAULT_BURST - 1);

        // then
        assertThat(healed.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
        assertThat(healed.suppressedCount()).isEqualTo(3L);
        assertThat(remainingBurst)
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT);
    }

    @Test
    void transitionDiscardsResidualCreditBeforeHeal() {
        // given
        long transitionAt = Duration.ofSeconds(6L).toNanos();
        AtomicLong beforeClock = new AtomicLong();
        SeverityThrottle beforeBoundary = throttle(DEFAULT_BURST, beforeClock);
        enterSlowModeWithResidualCredit(beforeBoundary, beforeClock, transitionAt);
        AtomicLong boundaryClock = new AtomicLong();
        SeverityThrottle atBoundary = throttle(DEFAULT_BURST, boundaryClock);
        enterSlowModeWithResidualCredit(atBoundary, boundaryClock, transitionAt);

        // when
        beforeClock.set(transitionAt + INTERVAL_NANOS - 1L);
        SeverityThrottle.Acquisition before = beforeBoundary.tryAcquire();
        boundaryClock.set(transitionAt + INTERVAL_NANOS);
        SeverityThrottle.Acquisition exact = atBoundary.tryAcquire();

        // then
        assertThat(before.decision()).isEqualTo(SeverityThrottle.Decision.DENY);
        assertThat(exact.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
    }

    @Test
    void attemptEveryFiftySecondsHealsOnSecondAttempt() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);

        // when
        clock.set(Duration.ofSeconds(50L).toNanos());
        SeverityThrottle.Acquisition first = throttle.tryAcquire();
        clock.set(Duration.ofSeconds(100L).toNanos());
        SeverityThrottle.Acquisition second = throttle.tryAcquire();
        SeverityThrottle.Acquisition fastFollower = throttle.tryAcquire();

        // then
        assertThat(first.decision()).isEqualTo(SeverityThrottle.Decision.DENY);
        assertThat(second.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
        assertThat(second.suppressedCount()).isOne();
        assertThat(fastFollower.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
    }

    @Test
    void attemptEveryFiveSecondsDoesNotHealForTenIntervals() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);

        // when
        List<SeverityThrottle.Acquisition> acquisitions = new ArrayList<>();
        for (int second = 5; second <= 600; second += 5) {
            clock.set(Duration.ofSeconds(second).toNanos());
            acquisitions.add(throttle.tryAcquire());
        }

        // then
        assertThat(acquisitions)
                .extracting(SeverityThrottle.Acquisition::decision)
                .containsOnly(SeverityThrottle.Decision.ADMIT, SeverityThrottle.Decision.DENY);
        assertThat(acquisitions)
                .filteredOn(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT)
                .hasSize(10);
        assertThat(acquisitions)
                .filteredOn(acquisition -> acquisition.decision() == SeverityThrottle.Decision.DENY)
                .hasSize(110);
    }

    @Test
    void slowAdmissionDoesNotAccumulateUnusedScheduleTime() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);
        for (int second = 5; second <= 55; second += 5) {
            clock.set(Duration.ofSeconds(second).toNanos());
            assertThat(throttle.tryAcquire().decision()).isEqualTo(SeverityThrottle.Decision.DENY);
        }

        // when
        clock.set(Duration.ofSeconds(65L).toNanos());
        SeverityThrottle.Acquisition delayedAdmission = throttle.tryAcquire();
        clock.set(Duration.ofSeconds(120L).toNanos());
        SeverityThrottle.Acquisition beforeNextBoundary = throttle.tryAcquire();

        // then
        assertThat(delayedAdmission.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
        assertThat(beforeNextBoundary.decision()).isEqualTo(SeverityThrottle.Decision.DENY);
    }

    @Test
    void slowDeadlineSurvivesNanoTimeWraparound() {
        // given
        long initialTime = Long.MAX_VALUE - 5L;
        AtomicLong clock = new AtomicLong(initialTime);
        SeverityThrottle throttle = new SeverityThrottle(1, Duration.ofNanos(10L), clock::get);
        enterSlowMode(throttle, 1);

        // when
        clock.set(Long.MIN_VALUE + 3L);
        SeverityThrottle.Acquisition beforeBoundary = throttle.tryAcquire();
        clock.set(Long.MIN_VALUE + 4L);
        SeverityThrottle.Acquisition atBoundary = throttle.tryAcquire();

        // then
        assertThat(beforeBoundary.decision()).isEqualTo(SeverityThrottle.Decision.DENY);
        assertThat(atBoundary.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
        assertThat(atBoundary.suppressedCount()).isOne();
    }

    @Test
    void healedThrottleCanEmitAnotherTransitionMarker() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);
        assertThat(throttle.tryAcquire().decision()).isEqualTo(SeverityThrottle.Decision.DENY);
        clock.set(INTERVAL_NANOS);
        assertThat(throttle.tryAcquire().decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
        assertThat(acquire(throttle, DEFAULT_BURST - 1))
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT);

        // when
        SeverityThrottle.Acquisition repeatedTransition = throttle.tryAcquire();

        // then
        assertThat(repeatedTransition.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT_WITH_MARKER);
    }

    @Test
    void preservesEveryAttemptAcrossSuppressionAndHeal() {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(2, clock);
        long attempts = 0L;
        long admitted = 0L;
        long reportedSuppressed = 0L;
        for (int index = 0; index < 3; index++) {
            SeverityThrottle.Acquisition acquisition = throttle.tryAcquire();
            attempts++;
            admitted++;
            reportedSuppressed += acquisition.suppressedCount();
        }
        for (int index = 0; index < 7; index++) {
            SeverityThrottle.Acquisition acquisition = throttle.tryAcquire();
            attempts++;
            reportedSuppressed += acquisition.suppressedCount();
        }

        // when
        clock.set(INTERVAL_NANOS);
        SeverityThrottle.Acquisition flush = throttle.tryAcquire();
        admitted++;
        reportedSuppressed += flush.suppressedCount();

        // then
        assertThat(attempts + 1L).isEqualTo(admitted + reportedSuppressed);
    }

    @Test
    void concurrentInitialAttemptsPreserveFastAndSlowCardinality() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);

        // when
        List<SeverityThrottle.Acquisition> acquisitions = acquireConcurrently(throttle);

        // then
        assertCardinality(acquisitions);
    }

    @Test
    void concurrentHealPreservesSuppressionAndStartsOneNewSlowEpisode() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = throttle(DEFAULT_BURST, clock);
        enterSlowMode(throttle, DEFAULT_BURST);
        assertThat(acquire(throttle, 3))
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.DENY);
        clock.set(INTERVAL_NANOS);

        // when
        List<SeverityThrottle.Acquisition> acquisitions = acquireConcurrently(throttle);

        // then
        assertCardinality(acquisitions);
        assertThat(acquisitions)
                .filteredOn(acquisition -> acquisition.suppressedCount() > 0L)
                .singleElement()
                .satisfies(acquisition -> {
                    assertThat(acquisition.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
                    assertThat(acquisition.suppressedCount()).isEqualTo(3L);
                });
    }

    @Test
    void overflowSafeRefillSaturatesLargeValidCapacity() {
        // given
        long intervalNanos = Long.MAX_VALUE / 2L;
        AtomicLong clock = new AtomicLong();
        SeverityThrottle throttle = new SeverityThrottle(2, Duration.ofNanos(intervalNanos), clock::get);
        assertThat(throttle.tryAcquire().decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);

        // when
        clock.set(intervalNanos);
        SeverityThrottle.Acquisition acquisition = throttle.tryAcquire();

        // then
        assertThat(acquisition.decision()).isEqualTo(SeverityThrottle.Decision.ADMIT);
    }

    @Test
    void rejectsInvalidConfiguration() {
        // given
        Duration overflowingInterval = Duration.ofSeconds(Long.MAX_VALUE);
        Duration overflowingCapacity = Duration.ofNanos((Long.MAX_VALUE / 2L) + 1L);

        // when
        // then
        assertThatThrownBy(() -> new SeverityThrottle(0, INTERVAL, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("burst must be greater than zero");
        assertThatThrownBy(() -> new SeverityThrottle(1, Duration.ZERO, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("interval must be greater than zero");
        assertThatThrownBy(() -> new SeverityThrottle(1, Duration.ofNanos(-1L), System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("interval must be greater than zero");
        assertThatThrownBy(() -> new SeverityThrottle(1, overflowingInterval, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new SeverityThrottle(2, overflowingCapacity, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new SeverityThrottle(1, null, System::nanoTime))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("interval");
        assertThatThrownBy(() -> new SeverityThrottle(1, INTERVAL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nanoClock");
    }

    private static SeverityThrottle throttle(int burst, AtomicLong clock) {
        return new SeverityThrottle(burst, INTERVAL, clock::get);
    }

    private static void enterSlowMode(SeverityThrottle throttle, int burst) {
        assertThat(acquire(throttle, burst))
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT);
        assertThat(throttle.tryAcquire().decision()).isEqualTo(SeverityThrottle.Decision.ADMIT_WITH_MARKER);
    }

    private static void enterSlowModeWithResidualCredit(SeverityThrottle throttle,
                                                        AtomicLong clock,
                                                        long transitionAt) {
        assertThat(acquire(throttle, DEFAULT_BURST))
                .allMatch(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT);
        clock.set(transitionAt);
        assertThat(throttle.tryAcquire().decision()).isEqualTo(SeverityThrottle.Decision.ADMIT_WITH_MARKER);
    }

    private static List<SeverityThrottle.Acquisition> acquire(SeverityThrottle throttle, int count) {
        List<SeverityThrottle.Acquisition> acquisitions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            acquisitions.add(throttle.tryAcquire());
        }
        return acquisitions;
    }

    private static List<SeverityThrottle.Acquisition> acquireConcurrently(SeverityThrottle throttle) throws Exception {
        CyclicBarrier start = new CyclicBarrier(CONCURRENT_ATTEMPTS);
        List<Callable<SeverityThrottle.Acquisition>> attempts = new ArrayList<>(CONCURRENT_ATTEMPTS);
        for (int index = 0; index < CONCURRENT_ATTEMPTS; index++) {
            attempts.add(() -> {
                start.await();
                return throttle.tryAcquire();
            });
        }

        List<SeverityThrottle.Acquisition> acquisitions = new ArrayList<>(CONCURRENT_ATTEMPTS);
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS)) {
            List<Future<SeverityThrottle.Acquisition>> completed = executor.invokeAll(attempts);
            for (Future<SeverityThrottle.Acquisition> attempt : completed) {
                acquisitions.add(attempt.get());
            }
        }
        return acquisitions;
    }

    private static void assertCardinality(List<SeverityThrottle.Acquisition> acquisitions) {
        assertThat(acquisitions)
                .filteredOn(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT)
                .hasSize(DEFAULT_BURST);

        assertThat(acquisitions)
                .filteredOn(acquisition -> acquisition.decision() == SeverityThrottle.Decision.ADMIT_WITH_MARKER)
                .hasSize(1);

        assertThat(acquisitions)
                .filteredOn(acquisition -> acquisition.decision() == SeverityThrottle.Decision.DENY)
                .hasSize(CONCURRENT_ATTEMPTS - DEFAULT_BURST - 1);
    }
}
