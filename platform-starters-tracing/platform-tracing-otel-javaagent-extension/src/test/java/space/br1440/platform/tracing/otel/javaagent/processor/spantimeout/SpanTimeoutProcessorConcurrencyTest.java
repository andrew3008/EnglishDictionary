package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanTimeoutProcessorConcurrencyTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    @Test
    void scanner_and_onEnd_are_serialized_on_trace_state() throws Exception {
        // given
        AtomicLong clock = new AtomicLong();
        CountDownLatch scannerEnteredState = new CountDownLatch(1);
        CountDownLatch releaseScanner = new CountDownLatch(1);
        ReadWriteSpan span = spanHoldingScannerInsideState(scannerEnteredState, releaseScanner);

        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofNanos(1), Duration.ofHours(1), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            spanTimeout.onStart(Context.root(), span);
            clock.set(2L);

            // when
            Future<?> scan = executor.submit(spanTimeout::forceFlush);
            assertThat(scannerEnteredState.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            Future<?> end = executor.submit(() -> spanTimeout.onEnd(span));
            releaseScanner.countDown();
            scan.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
            end.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getActiveTraceCount()).isZero();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(1);
            verify(span).end();
        }
    }

    private static ReadWriteSpan spanHoldingScannerInsideState(CountDownLatch entered, CountDownLatch release) {
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);
        when(span.hasEnded()).thenAnswer(ignored -> {
            entered.countDown();
            assertThat(release.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return false;
        });
        return span;
    }
}
