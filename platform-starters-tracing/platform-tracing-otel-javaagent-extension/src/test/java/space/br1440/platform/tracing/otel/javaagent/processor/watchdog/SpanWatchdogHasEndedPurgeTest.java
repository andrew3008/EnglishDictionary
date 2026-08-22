package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class SpanWatchdogHasEndedPurgeTest {

    @Test
    void endedSpanIsPurgedWithoutForceClose() {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("purge");
        Span span = tracer.spanBuilder("ended-outside").startSpan();
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(1);
        span.end();

        // when
        watchdog.scanForTesting();

        // then
        assertThat(watchdog.getActiveSpanCount()).isZero();
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void exactDeadlineDoesNotForceClose() {
        // given
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofMillis(10);
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                timeout, timeout, clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("deadline");
        Span span = tracer.spanBuilder("boundary").startSpan();
        clock.set(timeout.toNanos());

        // when
        watchdog.scanForTesting();

        // then
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();
        clock.addAndGet(1L);
        watchdog.scanForTesting();
        assertThat(watchdog.getForcedSpanCloses() + watchdog.getForcedTraceCloses()).isEqualTo(1);
        span.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void sameSpanDoesNotIncrementBothCounters() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(10), Duration.ofMillis(10), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("exclusive");
        Span span = tracer.spanBuilder("both").startSpan();
        clock.set(Duration.ofMillis(20).toNanos());

        // when
        watchdog.scanForTesting();

        // then
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(1);
        assertThat(watchdog.getForcedTraceCloses()).isZero();
        span.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void clearedWeakReferenceIsPurgedWithoutForceClose() {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("gc");
        AtomicReference<Span> holder = new AtomicReference<>(tracer.spanBuilder("weak").startSpan());
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(1);
        holder.set(null);

        // when
        boolean collected = tryClearWeakReference();
        watchdog.scanForTesting();

        // then
        assumeTrue(collected && watchdog.getActiveSpanCount() == 0);
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    private static boolean tryClearWeakReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            System.gc();
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return true;
    }
}
