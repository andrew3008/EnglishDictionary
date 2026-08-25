package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanWatchdogAdmissionSemanticsTest {

    @Test
    void duplicateSpanIdIsNoOpAndDoesNotTouchRejectionCounters() {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 1, 1);
        ReadWriteSpan span = WatchdogTestSpans.span(1, 1);
        watchdog.onStart(Context.root(), span);

        // when
        watchdog.onStart(Context.root(), span);

        // then
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(1);
        assertThat(watchdog.getSpansRejectedPerTraceTotal()).isZero();
        assertThat(watchdog.getSpansRejectedGlobalTotal()).isZero();
        watchdog.shutdown();
    }

    @Test
    void dualSaturationIncrementsOnlyPerTraceRejectionCounter() {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 1, 1);
        watchdog.onStart(Context.root(), WatchdogTestSpans.span(1, 1));

        // when
        watchdog.onStart(Context.root(), WatchdogTestSpans.span(1, 2));

        // then
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(1);
        assertThat(watchdog.getSpansRejectedPerTraceTotal()).isEqualTo(1);
        assertThat(watchdog.getSpansRejectedGlobalTotal()).isZero();
        watchdog.shutdown();
    }
}
