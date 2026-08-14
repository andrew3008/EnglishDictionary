package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import org.junit.jupiter.api.Test;

class SpanWatchdogProcessorConcurrencyTest {

    @Test
    void scanner_и_onEnd_сериализованы_на_trace_state() throws Exception {
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofNanos(1), Duration.ofHours(1), clock::get);
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        SpanContext spanContext = SpanContext.create(
                "00000000000000000000000000000001",
                "0000000000000001",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        when(span.getSpanContext()).thenReturn(spanContext);

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
        try {
            Future<?> scan = executor.submit(watchdog::scanForTesting);
            assertThat(scannerEnteredState.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> end = executor.submit(() -> watchdog.onEnd(span));

            releaseScanner.countDown();
            scan.get(5, TimeUnit.SECONDS);
            end.get(5, TimeUnit.SECONDS);

            assertThat(watchdog.getActiveSpanCount()).isZero();
            assertThat(watchdog.getActiveTraceCount()).isZero();
            assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);
            verify(span).end();
        } finally {
            releaseScanner.countDown();
            executor.shutdownNow();
            watchdog.shutdown();
        }
    }
}
