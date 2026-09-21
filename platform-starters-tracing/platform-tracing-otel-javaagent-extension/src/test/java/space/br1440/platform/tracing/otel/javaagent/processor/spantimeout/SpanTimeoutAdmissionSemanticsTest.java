package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanTimeoutAdmissionSemanticsTest {

    @Test
    void duplicateSpanIdIsNoOpAndDoesNotTouchRejectionCounters() {
        // given
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 1, 1);
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);
        spanTimeout.onStart(Context.root(), span);

        // when
        spanTimeout.onStart(Context.root(), span);

        // then
        assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
        assertThat(spanTimeout.getSpansRejectedPerTraceTotal()).isZero();
        assertThat(spanTimeout.getSpansRejectedGlobalTotal()).isZero();
        spanTimeout.shutdown();
    }

    @Test
    void dualSaturationIncrementsOnlyPerTraceRejectionCounter() {
        // given
        SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 1, 1);
        spanTimeout.onStart(Context.root(), SpanTimeoutTestSpans.span(1, 1));

        // when
        spanTimeout.onStart(Context.root(), SpanTimeoutTestSpans.span(1, 2));

        // then
        assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
        assertThat(spanTimeout.getSpansRejectedPerTraceTotal()).isEqualTo(1);
        assertThat(spanTimeout.getSpansRejectedGlobalTotal()).isZero();
        spanTimeout.shutdown();
    }
}
