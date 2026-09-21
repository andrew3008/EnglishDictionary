package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class SpanTimeoutHasEndedPurgeTest {

    @Test
    void endedSpanIsPurgedWithoutForceClose() {
        // given
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("purge");
        Span span = tracer.spanBuilder("ended-outside").startSpan();
        assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
        span.end();

        // when
        spanTimeout.scanForTesting();

        // then
        assertThat(spanTimeout.getActiveSpanCount()).isZero();
        assertThat(spanTimeout.getForcedSpanCloses()).isZero();
        assertThat(spanTimeout.getForcedTraceCloses()).isZero();
        spanTimeout.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void exactDeadlineDoesNotForceClose() {
        // given
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofMillis(10);
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                timeout, timeout, clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("deadline");
        Span span = tracer.spanBuilder("boundary").startSpan();
        clock.set(timeout.toNanos());

        // when
        spanTimeout.scanForTesting();

        // then
        assertThat(spanTimeout.getForcedSpanCloses()).isZero();
        assertThat(spanTimeout.getForcedTraceCloses()).isZero();
        clock.addAndGet(1L);
        spanTimeout.scanForTesting();
        assertThat(spanTimeout.getForcedSpanCloses() + spanTimeout.getForcedTraceCloses()).isEqualTo(1);
        span.end();
        spanTimeout.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void sameSpanDoesNotIncrementBothCounters() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(10), Duration.ofMillis(10), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("exclusive");
        Span span = tracer.spanBuilder("both").startSpan();
        clock.set(Duration.ofMillis(20).toNanos());

        // when
        spanTimeout.scanForTesting();

        // then
        assertThat(spanTimeout.getForcedSpanCloses()).isEqualTo(1);
        assertThat(spanTimeout.getForcedTraceCloses()).isZero();
        span.end();
        spanTimeout.shutdown();
        tracerProvider.shutdown();
    }

    @Test
    void clearedWeakReferenceIsPurgedWithoutForceClose() throws ReflectiveOperationException {
        // given
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), System::nanoTime,
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("gc");
        Span span = tracer.spanBuilder("weak").startSpan();
        assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
        clearTrackedSpanReference(spanTimeout);

        // when
        spanTimeout.scanForTesting();

        // then
        assertThat(spanTimeout.getActiveSpanCount()).isZero();
        assertThat(spanTimeout.getForcedSpanCloses()).isZero();
        assertThat(spanTimeout.getForcedTraceCloses()).isZero();
        span.end();
        spanTimeout.shutdown();
        tracerProvider.shutdown();
    }

    @SuppressWarnings("unchecked")
    private static void clearTrackedSpanReference(SpanTimeoutProcessor spanTimeout) throws ReflectiveOperationException {
        Field indexField = SpanTimeoutProcessor.class.getDeclaredField("index");
        indexField.setAccessible(true);
        InFlightTraceIndex index = (InFlightTraceIndex) indexField.get(spanTimeout);

        Field tracesField = InFlightTraceIndex.class.getDeclaredField("traces");
        tracesField.setAccessible(true);
        Map<String, InFlightTraceState> traces = (Map<String, InFlightTraceState>) tracesField.get(index);
        assertThat(traces.values()).singleElement().satisfies(state ->
                assertThat(state.records()).singleElement().satisfies(tracked ->
                        tracked.spanRef().clear()));
    }
}
