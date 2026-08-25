package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

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

class SpanWatchdogBoundedStateTest {

    @Test
    void overflowingTracesEvictOldestWithoutForceClose() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMinutes(1), Duration.ofMinutes(1), clock::get, 3, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(watchdog)
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("bound");
        List<Span> spans = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            clock.set(Duration.ofMillis(index).toNanos());
            spans.add(tracer.spanBuilder("trace-" + index).startSpan());
        }

        // when
        watchdog.scanForTesting();

        // then
        assertThat(watchdog.getTrackedTraceCapacity()).isEqualTo(3);
        assertThat(watchdog.getTrackedTraceCount()).isLessThanOrEqualTo(3);
        assertThat(watchdog.getTrackingEvictedTotal()).isEqualTo(2);
        assertThat(watchdog.getForcedSpanCloses()).isZero();
        assertThat(watchdog.getForcedTraceCloses()).isZero();
        clock.set(Duration.ofMinutes(2).toNanos());
        watchdog.scanForTesting();
        assertThat(watchdog.getForcedSpanCloses() + watchdog.getForcedTraceCloses()).isEqualTo(3);
        for (Span span : spans) {
            span.end();
        }

        watchdog.shutdown();
        tracerProvider.shutdown();
    }
}
