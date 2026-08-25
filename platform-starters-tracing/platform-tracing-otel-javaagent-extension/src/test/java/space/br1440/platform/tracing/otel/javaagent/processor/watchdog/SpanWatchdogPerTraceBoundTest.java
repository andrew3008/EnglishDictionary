package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanWatchdogPerTraceBoundTest {

    @Test
    void perTraceCapacityRejectsExcessSpansWithoutForceClosingThem() {
        // given
        AtomicLong clock = new AtomicLong();
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofMillis(10), Duration.ofMinutes(1), clock::get, 10, 3, 100);
        List<ReadWriteSpan> spans = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            spans.add(WatchdogTestSpans.span(1, index));
        }

        // when
        for (ReadWriteSpan span : spans) {
            watchdog.onStart(Context.root(), span);
        }

        // then
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(3);
        assertThat(watchdog.getSpansRejectedPerTraceTotal()).isEqualTo(2);
        assertThat(watchdog.getSpansRejectedGlobalTotal()).isZero();
        clock.set(Duration.ofMillis(20).toNanos());
        watchdog.scanForTesting();
        assertThat(watchdog.getForcedSpanCloses()).isEqualTo(3);
        verify(spans.get(3), never()).end();
        verify(spans.get(4), never()).end();
        assertThat(watchdog.getActiveSpanCount()).isZero();

        ReadWriteSpan resumed = WatchdogTestSpans.span(1, 6);
        watchdog.onStart(Context.root(), resumed);
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(1);
        assertThat(watchdog.getSpansRejectedPerTraceTotal()).isEqualTo(2);
        watchdog.shutdown();
    }
}
