package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;

class SpanTimeoutRejectedTraceFloodTest {

    @Test
    void rejectedUniqueTraceIdsDoNotEvictAdmittedTraces() {
        // given
        int maxTraces = 4;
        int maxSpans = 3;
        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, maxTraces, 32, maxSpans)) {
            for (int index = 1; index <= maxSpans; index++) {
                spanTimeout.onStart(Context.root(), SpanTimeoutTestSpans.span(index, 1));
            }

            // when
            for (int index = 1; index <= (100 * maxTraces); index++) {
                spanTimeout.onStart(Context.root(), SpanTimeoutTestSpans.span(1_000 + index, 1));
            }

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(maxSpans);
            assertThat(spanTimeout.getTrackedTraceCount()).isLessThanOrEqualTo(maxTraces);
            assertThat(spanTimeout.getTrackedTraceCount()).isEqualTo(maxSpans);
            assertThat(spanTimeout.getTrackingEvictedTotal()).isZero();
            assertThat(spanTimeout.getSpansRejectedGlobalTotal()).isEqualTo(100L * maxTraces);
            assertThat(spanTimeout.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }
}
