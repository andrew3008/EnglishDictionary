package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;

class SpanWatchdogRejectedTraceFloodTest {

    @Test
    void rejectedUniqueTraceIdsDoNotEvictAdmittedTraces() {
        // given
        int maxTraces = 4;
        int maxSpans = 3;
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, maxTraces, 32, maxSpans);
        for (int index = 1; index <= maxSpans; index++) {
            watchdog.onStart(Context.root(), WatchdogTestSpans.span(index, 1));
        }

        // when
        for (int index = 1; index <= (100 * maxTraces); index++) {
            watchdog.onStart(Context.root(), WatchdogTestSpans.span(1_000 + index, 1));
        }

        // then
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(maxSpans);
        assertThat(watchdog.getTrackedTraceCount()).isLessThanOrEqualTo(maxTraces);
        assertThat(watchdog.getTrackedTraceCount()).isEqualTo(maxSpans);
        assertThat(watchdog.getTrackingEvictedTotal()).isZero();
        assertThat(watchdog.getSpansRejectedGlobalTotal()).isEqualTo(100L * maxTraces);
        assertThat(watchdog.getSpanLedgerInvariantViolationTotal()).isZero();
        watchdog.shutdown();
    }
}
