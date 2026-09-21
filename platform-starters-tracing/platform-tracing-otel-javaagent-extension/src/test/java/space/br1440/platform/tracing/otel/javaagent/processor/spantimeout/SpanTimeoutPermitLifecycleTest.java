package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
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

class SpanTimeoutPermitLifecycleTest {

    @Test
    void concurrentScanAndOnEndReleasePermitOnce() throws Exception {
        // given
        AtomicLong testNanoTime = new AtomicLong();
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);
        CountDownLatch spanStateCheckStarted = new CountDownLatch(1);
        CountDownLatch continueScanner = new CountDownLatch(1);
        when(span.hasEnded()).thenAnswer(ignored -> {
            spanStateCheckStarted.countDown();
            assertThat(continueScanner.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        });

        try (SpanTimeoutProcessor spanTimeoutProcessor = new SpanTimeoutProcessor(
                Duration.ofNanos(1), Duration.ofHours(1), testNanoTime::get, 10, 10, 10
        );
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            spanTimeoutProcessor.onStart(Context.root(), span);
            testNanoTime.set(2L);

            // when
            Future<?> scanFuture = executorService.submit(spanTimeoutProcessor::forceFlush);
            assertThat(spanStateCheckStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> endFuture = executorService.submit(() -> spanTimeoutProcessor.onEnd(span));
            continueScanner.countDown();
            scanFuture.get(5, TimeUnit.SECONDS);
            endFuture.get(5, TimeUnit.SECONDS);

            // then
            assertThat(spanTimeoutProcessor.getActiveSpanCount()).isZero();
            assertThat(spanTimeoutProcessor.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }

    @Test
    void extractedCandidateKeepsItsPermitUntilTheEndAttemptCompletes() throws Exception {
        // given
        AtomicLong testNanoTime = new AtomicLong();
        ReadWriteSpan expired = SpanTimeoutTestSpans.span(1, 1);
        ReadWriteSpan fresh = SpanTimeoutTestSpans.span(2, 1);
        CountDownLatch endStarted = new CountDownLatch(1);
        CountDownLatch releaseEnd = new CountDownLatch(1);
        doAnswer(ignored -> {
            endStarted.countDown();
            assertThat(releaseEnd.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(expired).end();

        try (SpanTimeoutProcessor spanTimeoutProcessor = new SpanTimeoutProcessor(
                Duration.ofNanos(1), Duration.ofHours(1), testNanoTime::get, 10, 10, 2
        );
             ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            spanTimeoutProcessor.onStart(Context.root(), expired);
            testNanoTime.set(2L);
            spanTimeoutProcessor.onStart(Context.root(), fresh);

            // when
            Future<?> scanFuture = executorService.submit(spanTimeoutProcessor::forceFlush);
            assertThat(endStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // then
            assertThat(spanTimeoutProcessor.getActiveSpanCount()).isEqualTo(2);
            spanTimeoutProcessor.onStart(Context.root(), SpanTimeoutTestSpans.span(3, 1));
            assertThat(spanTimeoutProcessor.getSpansRejectedGlobalTotal()).isEqualTo(1);

            Future<?> parallelFlush = executorService.submit(spanTimeoutProcessor::forceFlush);
            parallelFlush.get(5, TimeUnit.SECONDS);
            assertThat(spanTimeoutProcessor.getActiveSpanCount()).isEqualTo(2);

            releaseEnd.countDown();
            scanFuture.get(5, TimeUnit.SECONDS);

            assertThat(spanTimeoutProcessor.getActiveSpanCount()).isEqualTo(1);
            assertThat(spanTimeoutProcessor.getForcedSpanEndAttemptsTotal()).isEqualTo(1);
            assertThat(spanTimeoutProcessor.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }

    @Test
    void concurrentShutdownAndOnEndReleaseEachPermitOnce() throws Exception {
        // given
        List<ReadWriteSpan> spanIndex = new ArrayList<>();
        CyclicBarrier startBarrier = new CyclicBarrier(6);

        try (SpanTimeoutProcessor spanTimeoutProcessor = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10
        );
             ExecutorService executorService = Executors.newFixedThreadPool(6)) {
            for (int index = 1; index <= 5; index++) {
                ReadWriteSpan span = SpanTimeoutTestSpans.span(1, index);
                spanIndex.add(span);
                spanTimeoutProcessor.onStart(Context.root(), span);
            }

            // when
            Future<?> shutdownFuture = executorService.submit(() -> {
                startBarrier.await(5, TimeUnit.SECONDS);
                spanTimeoutProcessor.shutdown();
                return null;
            });
            List<Future<?>> endFutures = new ArrayList<>();
            for (ReadWriteSpan span : spanIndex) {
                endFutures.add(executorService.submit(() -> {
                    startBarrier.await(5, TimeUnit.SECONDS);
                    spanTimeoutProcessor.onEnd(span);
                    return null;
                }));
            }
            shutdownFuture.get(5, TimeUnit.SECONDS);
            for (Future<?> endFuture : endFutures) {
                endFuture.get(5, TimeUnit.SECONDS);
            }

            // then
            assertThat(spanTimeoutProcessor.getActiveSpanCount()).isZero();
            assertThat(spanTimeoutProcessor.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }

    @Test
    void shutdownClearsIndexAndReleasesAllPermits() {
        // given
        try (SpanTimeoutProcessor spanTimeoutProcessor = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10
        )) {
            for (int index = 1; index <= 4; index++) {
                spanTimeoutProcessor.onStart(Context.root(), SpanTimeoutTestSpans.span(index, 1));
            }

            // when
            spanTimeoutProcessor.shutdown();

            // then
            assertThat(spanTimeoutProcessor.getActiveSpanCount()).isZero();
            assertThat(spanTimeoutProcessor.getTrackedTraceCount()).isZero();
            assertThat(spanTimeoutProcessor.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }

    @Test
    void scanOfActiveSpansDoesNotRecordInvariantViolation() {
        // given
        try (SpanTimeoutProcessor spanTimeoutProcessor = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10
        )) {
            spanTimeoutProcessor.onStart(Context.root(), SpanTimeoutTestSpans.span(1, 1));
            spanTimeoutProcessor.onStart(Context.root(), SpanTimeoutTestSpans.span(2, 1));

            // when
            assertThat(spanTimeoutProcessor.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeoutProcessor.getSpanPermitInvariantViolationTotal()).isZero();
            assertThat(spanTimeoutProcessor.getActiveSpanCount()).isEqualTo(2);
        }
    }
}
