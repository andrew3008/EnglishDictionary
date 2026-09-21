package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("purge");
            Span span = tracer.spanBuilder("ended-outside").startSpan();
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
            span.end();

            // when
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void exactDeadlineDoesNotForceClose() {
        // given
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofMillis(10);
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                timeout, timeout, clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("deadline");
            Span span = tracer.spanBuilder("boundary").startSpan();
            clock.set(timeout.toNanos());

            // when
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();
            clock.addAndGet(1L);
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal() + spanTimeout.getForcedTraceEndAttemptsTotal()).isEqualTo(1);
            span.end();
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void sameSpanDoesNotIncrementBothCounters() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(10), Duration.ofMillis(10), clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("exclusive");
            Span span = tracer.spanBuilder("both").startSpan();
            clock.set(Duration.ofMillis(20).toNanos());

            // when
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(1);
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();
            span.end();
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }
}
