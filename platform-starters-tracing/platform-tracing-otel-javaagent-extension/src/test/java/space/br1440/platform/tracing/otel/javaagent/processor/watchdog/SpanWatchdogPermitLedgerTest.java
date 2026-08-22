package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanWatchdogPermitLedgerTest {

    @Test
    void onEndAndForceCloseReleaseTheSameSpanExactlyOnce() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofNanos(1), Duration.ofHours(1), clock::get, 10, 10, 10);
        ReadWriteSpan span = WatchdogTestSpans.span(1, 1);
        CountDownLatch scannerEnteredState = new CountDownLatch(1);
        CountDownLatch releaseScanner = new CountDownLatch(1);
        when(span.hasEnded()).thenAnswer(ignored -> {
            scannerEnteredState.countDown();
            assertThat(releaseScanner.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        });
        watchdog.onStart(Context.root(), span);
        clock.set(2L);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        Future<?> scan = executor.submit(watchdog::scanForTesting);
        assertThat(scannerEnteredState.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> end = executor.submit(() -> watchdog.onEnd(span));
        releaseScanner.countDown();
        scan.get(5, TimeUnit.SECONDS);
        end.get(5, TimeUnit.SECONDS);

        // then
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getSpanLedgerInvariantViolationTotal()).isZero();
        executor.shutdownNow();
        watchdog.shutdown();
    }

    @Test
    void shutdownDrainAndConcurrentOnEndAccountEveryEntryExactlyOnce() throws Exception {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10);
        List<ReadWriteSpan> spans = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            ReadWriteSpan span = WatchdogTestSpans.span(1, index);
            spans.add(span);
            watchdog.onStart(Context.root(), span);
        }
        CyclicBarrier barrier = new CyclicBarrier(6);
        ExecutorService executor = Executors.newFixedThreadPool(6);

        // when
        Future<?> shutdown = executor.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            watchdog.shutdown();
            return null;
        });
        List<Future<?>> ends = new ArrayList<>();
        for (ReadWriteSpan span : spans) {
            ends.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                watchdog.onEnd(span);
                return null;
            }));
        }
        shutdown.get(5, TimeUnit.SECONDS);
        for (Future<?> end : ends) {
            end.get(5, TimeUnit.SECONDS);
        }

        // then
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getSpanLedgerInvariantViolationTotal()).isZero();
        executor.shutdownNow();
    }

    @Test
    void shutdownDrainsNonEmptyIndexAndLeavesLedgerAtZero() {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10);
        for (int index = 1; index <= 4; index++) {
            watchdog.onStart(Context.root(), WatchdogTestSpans.span(index, 1));
        }

        // when
        watchdog.shutdown();

        // then
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getTrackedTraceCount()).isZero();
        assertThat(watchdog.getSpanLedgerInvariantViolationTotal()).isZero();
    }
}
