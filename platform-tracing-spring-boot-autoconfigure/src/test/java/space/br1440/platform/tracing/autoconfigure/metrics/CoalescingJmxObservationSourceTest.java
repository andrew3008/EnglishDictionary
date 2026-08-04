package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoalescingJmxObservationSource}: N вызовов {@code get()} внутри coalescing-окна должны
 * приводить ровно к одному вызову underlying source; после истечения окна — к следующему.
 */
class CoalescingJmxObservationSourceTest {

    private static final long WINDOW_NANOS = 150_000_000L;

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
}
