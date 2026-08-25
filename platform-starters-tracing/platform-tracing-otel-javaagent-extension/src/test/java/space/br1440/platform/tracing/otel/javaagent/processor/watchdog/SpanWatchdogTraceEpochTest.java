package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class SpanWatchdogTraceEpochTest {

    @Test
    void intermittentTraceKeepsEpochAndClosesByTraceTimeout() {
        // given
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofMillis(20);
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                timeout, timeout, clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("epoch");
        Span first = tracer.spanBuilder("first").startSpan();
        first.end();
        watchdog.scanForTesting();
        assertThat(watchdog.getActiveTraceCount()).isZero();
        assertThat(watchdog.getTrackedTraceCount()).isEqualTo(1);

        clock.set(Duration.ofMillis(8).toNanos());
        Span second = tracer.spanBuilder("second")
                .setParent(Context.root().with(first))
                .startSpan();
        assertThat(watchdog.getActiveTraceCount()).isEqualTo(1);
        assertThat(watchdog.getTrackedTraceCount()).isEqualTo(1);

        // when
        clock.set(timeout.toNanos() + 1L);
        watchdog.scanForTesting();

        // then
        assertThat(watchdog.getForcedTraceCloses()).isEqualTo(1);
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getActiveTraceCount()).isZero();
        second.end();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void emptyEpochIsRemovedOnlyAfterTraceTimeout() {
        // given
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofMillis(10);
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                timeout, timeout, clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("tombstone");
        Span span = tracer.spanBuilder("ended").startSpan();
        span.end();
        watchdog.scanForTesting();
        assertThat(watchdog.getTrackedTraceCount()).isEqualTo(1);
        assertThat(watchdog.getActiveTraceCount()).isZero();

        // when
        clock.set(timeout.toNanos());
        watchdog.scanForTesting();

        // then
        assertThat(watchdog.getTrackedTraceCount()).isEqualTo(1);
        clock.addAndGet(1L);
        watchdog.scanForTesting();
        assertThat(watchdog.getTrackedTraceCount()).isZero();
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();
        watchdog.shutdown();
        tracerProvider.shutdown();
    }
}
