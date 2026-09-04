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

class SpanWatchdogPermitLifecycleTest {

    @Test
    void concurrentScanAndOnEndReleasePermitOnce() throws Exception {
        // given
        AtomicLong testNanoTime = new AtomicLong();
        ReadWriteSpan span = WatchdogTestSpans.span(1, 1);
        CountDownLatch spanStateCheckStarted = new CountDownLatch(1);
        CountDownLatch continueScanner = new CountDownLatch(1);
        when(span.hasEnded()).thenAnswer(ignored -> {
            spanStateCheckStarted.countDown();
            assertThat(continueScanner.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        });

        try (SpanWatchdogProcessor watchdogProcessor = new SpanWatchdogProcessor(
                Duration.ofNanos(1), Duration.ofHours(1), testNanoTime::get, 10, 10, 10
        );
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            watchdogProcessor.onStart(Context.root(), span);
            testNanoTime.set(2L);

            // when
            Future<?> scanFuture = executorService.submit(watchdogProcessor::scanForTesting);
            assertThat(spanStateCheckStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> endFuture = executorService.submit(() -> watchdogProcessor.onEnd(span));
            continueScanner.countDown();
            scanFuture.get(5, TimeUnit.SECONDS);
            endFuture.get(5, TimeUnit.SECONDS);

            // then
            assertThat(watchdogProcessor.getActiveSpanCount()).isZero();
            assertThat(watchdogProcessor.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }

    @Test
    void concurrentShutdownAndOnEndReleaseEachPermitOnce() throws Exception {
        // given
        List<ReadWriteSpan> spanIndex = new ArrayList<>();
        CyclicBarrier startBarrier = new CyclicBarrier(6);

        try (SpanWatchdogProcessor watchdogProcessor = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10
        );
             ExecutorService executorService = Executors.newFixedThreadPool(6)) {
            for (int index = 1; index <= 5; index++) {
                ReadWriteSpan span = WatchdogTestSpans.span(1, index);
                spanIndex.add(span);
                watchdogProcessor.onStart(Context.root(), span);
            }

            // when
            Future<?> shutdownFuture = executorService.submit(() -> {
                startBarrier.await(5, TimeUnit.SECONDS);
                watchdogProcessor.shutdown();
                return null;
            });
            List<Future<?>> endFutures = new ArrayList<>();
            for (ReadWriteSpan span : spanIndex) {
                endFutures.add(executorService.submit(() -> {
                    startBarrier.await(5, TimeUnit.SECONDS);
                    watchdogProcessor.onEnd(span);
                    return null;
                }));
            }
            shutdownFuture.get(5, TimeUnit.SECONDS);
            for (Future<?> endFuture : endFutures) {
                endFuture.get(5, TimeUnit.SECONDS);
            }

            // then
            assertThat(watchdogProcessor.getActiveSpanCount()).isZero();
            assertThat(watchdogProcessor.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }

    @Test
    void shutdownClearsIndexAndReleasesAllPermits() {
        // given
        try (SpanWatchdogProcessor watchdogProcessor = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10
        )) {
            for (int index = 1; index <= 4; index++) {
                watchdogProcessor.onStart(Context.root(), WatchdogTestSpans.span(index, 1));
            }

            // when
            watchdogProcessor.shutdown();

            // then
            assertThat(watchdogProcessor.getActiveSpanCount()).isZero();
            assertThat(watchdogProcessor.getTrackedTraceCount()).isZero();
            assertThat(watchdogProcessor.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }

    @Test
    void scanOfActiveSpansDoesNotRecordInvariantViolation() {
        // given
        try (SpanWatchdogProcessor watchdogProcessor = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10
        )) {
            watchdogProcessor.onStart(Context.root(), WatchdogTestSpans.span(1, 1));
            watchdogProcessor.onStart(Context.root(), WatchdogTestSpans.span(2, 1));

            // when
            watchdogProcessor.scanForTesting();

            // then
            assertThat(watchdogProcessor.getSpanPermitInvariantViolationTotal()).isZero();
            assertThat(watchdogProcessor.getActiveSpanCount()).isEqualTo(2);
        }
    }
}
