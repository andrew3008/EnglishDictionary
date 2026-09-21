package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class SpanTimeoutTraceEpochTest {

    @Test
    void intermittentTraceKeepsEpochAndClosesByTraceTimeout() {
        // given
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofMillis(20);
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                timeout, timeout, clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("epoch");
            Span first = tracer.spanBuilder("first").startSpan();
            first.end();
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();
            assertThat(spanTimeout.getActiveTraceCount()).isZero();
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(1);

            clock.set(Duration.ofMillis(8).toNanos());
            Span second = tracer.spanBuilder("second")
                    .setParent(Context.root().with(first))
                    .startSpan();
            assertThat(spanTimeout.getActiveTraceCount()).isEqualTo(1);
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(1);

            // when
            clock.set(timeout.toNanos() + 1L);
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isEqualTo(1);
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();
            assertThat(spanTimeout.getActiveTraceCount()).isZero();
            second.end();
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }

    @Test
    void emptyEpochIsRemovedOnlyAfterTraceTimeout() {
        // given
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofMillis(10);
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                timeout, timeout, clock::get, 10_000, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        try (OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()) {
            Tracer tracer = sdk.getTracer("tombstone");
            Span span = tracer.spanBuilder("ended").startSpan();
            span.end();
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(1);
            assertThat(spanTimeout.getActiveTraceCount()).isZero();

            // when
            clock.set(timeout.toNanos());
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();

            // then
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(1);
            clock.addAndGet(1L);
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();
            assertThat(spanTimeout.getTrackedTraceCount()).isZero();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isZero();
            assertThat(spanTimeout.getForcedTraceEndAttemptsTotal()).isZero();
            spanTimeout.shutdown();
            tracerProvider.shutdown();
        }
    }
}
