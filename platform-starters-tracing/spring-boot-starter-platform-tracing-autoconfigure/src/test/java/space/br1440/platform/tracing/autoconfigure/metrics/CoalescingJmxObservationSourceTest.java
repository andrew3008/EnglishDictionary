package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;

/**
 * {@link CoalescingJmxObservationSource}: N вызовов {@code get()} внутри coalescing-окна должны
 * приводить ровно к одному вызову underlying source; после истечения окна — к следующему.
 */
class CoalescingJmxObservationSourceTest {

    private static final long WINDOW_NANOS = 150_000_000L;
    private static final String FIRST_READER = "first-stale-reader";
    private static final String SECOND_READER = "second-stale-reader";

    @Test
    void staleReaderRechecksSnapshotAfterAcquiringRefreshLock() throws Exception {
        // given
        CountDownLatch firstReaderAtTicker = new CountDownLatch(1);
        CountDownLatch secondReaderAtTicker = new CountDownLatch(1);
        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        AtomicLong captures = new AtomicLong();
        MonotonicJmxCounterGroupBridge.Ticker ticker = () -> {
            if (FIRST_READER.equals(Thread.currentThread().getName())) {
                firstReaderAtTicker.countDown();
                await(secondReaderAtTicker);
            } else if (SECOND_READER.equals(Thread.currentThread().getName())) {
                secondReaderAtTicker.countDown();
                await(firstReadCompleted);
            }

            return 0L;
        };
        CoalescingJmxObservationSource<String> source = new CoalescingJmxObservationSource<>(
                () -> Optional.of("snapshot-" + captures.incrementAndGet()),
                ticker,
                WINDOW_NANOS
        );

        // when
        Optional<String> firstObserved;
        Optional<String> secondObserved;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Optional<String>> first = pool.submit(() -> {
                Thread.currentThread().setName(FIRST_READER);
                try {
                    return source.get();
                } finally {
                    firstReadCompleted.countDown();
                }
            });
            assertThat(firstReaderAtTicker.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Optional<String>> second = pool.submit(() -> {
                Thread.currentThread().setName(SECOND_READER);
                return source.get();
            });
            firstObserved = first.get(5, TimeUnit.SECONDS);
            secondObserved = second.get(5, TimeUnit.SECONDS);
        }

        // then
        assertThat(firstObserved).contains("snapshot-1");
        assertThat(secondObserved).contains("snapshot-1");
        assertThat(captures.get()).isEqualTo(1L);
    }

    @Test
    void coherenceFailureIsObservedAsEmptySnapshot() {
        // given
        AgentReadinessCoherenceException coherenceFailure =
                mock(AgentReadinessCoherenceException.class);
        CoalescingJmxObservationSource<String> source = new CoalescingJmxObservationSource<>(
                () -> {
                    throw coherenceFailure;
                },
                () -> 0L,
                WINDOW_NANOS
        );

        // when
        Optional<String> observed = source.get();

        // then
        assertThat(observed).isEmpty();
    }

    @Test
    void multipleGetsWithinWindowTriggerSingleUnderlyingCapture() {
        AtomicLong captures = new AtomicLong();
        AtomicLong clock = new AtomicLong(0L);
        CoalescingJmxObservationSource<String> source = new CoalescingJmxObservationSource<>(
                () -> {
                    captures.incrementAndGet();
                    return Optional.of("snapshot");
                },
                clock::get,
                WINDOW_NANOS);

        for (int i = 0; i < 5; i++) {
            clock.addAndGet(1_000_000L);
            assertThat(source.get()).contains("snapshot");
        }

        assertThat(captures.get()).isEqualTo(1L);
    }

    @Test
    void getAfterWindowExpiryTriggersNewUnderlyingCapture() {
        AtomicLong captures = new AtomicLong();
        AtomicLong clock = new AtomicLong(0L);
        CoalescingJmxObservationSource<String> source = new CoalescingJmxObservationSource<>(
                () -> {
                    long n = captures.incrementAndGet();
                    return Optional.of("snapshot-" + n);
                },
                clock::get,
                WINDOW_NANOS);

        assertThat(source.get()).contains("snapshot-1");

        clock.addAndGet(WINDOW_NANOS + 1L);
        assertThat(source.get()).contains("snapshot-2");

        assertThat(captures.get()).isEqualTo(2L);
    }

    @Test
    void emptyUnderlyingResultIsCachedForTheWindowLikeAnyOtherSnapshot() {
        AtomicLong captures = new AtomicLong();
        AtomicLong clock = new AtomicLong(0L);
        CoalescingJmxObservationSource<String> source = new CoalescingJmxObservationSource<>(
                () -> {
                    captures.incrementAndGet();
                    return Optional.empty();
                },
                clock::get,
                WINDOW_NANOS);

        assertThat(source.get()).isEmpty();
        assertThat(source.get()).isEmpty();

        assertThat(captures.get()).isEqualTo(1L);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was not released before the deadline");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the latch", e);
        }
    }
}
