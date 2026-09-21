package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class SpanTimeoutBoundedStateTest {

    @Test
    void overflowingTracesAreRejectedAtAdmissionAndLiveTracesAreNeverEvicted() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), clock::get, 3, Integer.MAX_VALUE, Integer.MAX_VALUE);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("bound");
            List<Span> spans = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                clock.set(Duration.ofMillis(index).toNanos());
                spans.add(tracer.spanBuilder("trace-" + index).startSpan());
            }

            // when
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeout.getTrackedTraceCapacity()).isEqualTo(3);
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(3);
            assertThat(spanTimeout.getActiveTraceCount()).isEqualTo(3);
            assertThat(spanTimeout.getSpansRejectedTraceCapacityTotal()).isEqualTo(2);
            assertThat(spanTimeout.getTrackingEvictedTotal()).isZero();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();

            clock.set(Duration.ofMinutes(2).toNanos());
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal() + spanTimeout.getForcedTraceEndAttemptsTotal()).isEqualTo(3);
            for (Span span : spans) {
                span.end();
            }

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void emptyStatesAreRelievedOnTheNextScanAfterATraceCapacityRejection() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(10), clock::get, 2, Integer.MAX_VALUE, Integer.MAX_VALUE
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();

        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("relief");
            tracer.spanBuilder("ended-a").startSpan().end();
            clock.set(Duration.ofMillis(1).toNanos());
            tracer.spanBuilder("ended-b").startSpan().end();
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(2);
            assertThat(spanTimeout.getActiveTraceCount()).isZero();

            // when
            Span rejected = tracer.spanBuilder("rejected").startSpan();
            assertThat(spanTimeout.getSpansRejectedTraceCapacityTotal()).isEqualTo(1);
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeout.getTrackingEvictedTotal()).isEqualTo(1);
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(1);
            Span admitted = tracer.spanBuilder("admitted").startSpan();
            assertThat(spanTimeout.getSpansRejectedTraceCapacityTotal()).isEqualTo(1);
            assertThat(spanTimeout.getActiveTraceCount()).isEqualTo(1);
            rejected.end();
            admitted.end();

            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }
}
