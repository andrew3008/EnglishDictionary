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
    void overflowingTracesEvictOldestWithoutForceClose() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), clock::get, 3, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanTimeout)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("bound");
        List<Span> spans = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            clock.set(Duration.ofMillis(index).toNanos());
            spans.add(tracer.spanBuilder("trace-" + index).startSpan());
        }

        // when
        spanTimeout.scanForTesting();

        // then
        assertThat(spanTimeout.getTrackedTraceCapacity()).isEqualTo(3);
        assertThat(spanTimeout.getTrackedTraceCount()).isLessThanOrEqualTo(3);
        assertThat(spanTimeout.getTrackingEvictedTotal()).isEqualTo(2);
        assertThat(spanTimeout.getForcedSpanCloses()).isZero();
        assertThat(spanTimeout.getForcedTraceCloses()).isZero();
        clock.set(Duration.ofMinutes(2).toNanos());
        spanTimeout.scanForTesting();
        assertThat(spanTimeout.getForcedSpanCloses() + spanTimeout.getForcedTraceCloses()).isEqualTo(3);
        for (Span span : spans) {
            span.end();
        }

        spanTimeout.shutdown();
        tracerProvider.shutdown();
    }
}
