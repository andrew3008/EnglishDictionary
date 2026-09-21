package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

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

class SpanTimeoutPerTraceBoundTest {

    @Test
    void perTraceCapacityRejectsExcessSpansWithoutForceClosingThem() {
        // given
        AtomicLong clock = new AtomicLong();
        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofMillis(10), Duration.ofMinutes(1), clock::get, 10, 3, 100
        )) {
            List<ReadWriteSpan> spans = new ArrayList<>();
            for (int index = 1; index <= 5; index++) {
                spans.add(SpanTimeoutTestSpans.span(1, index));
            }

            // when
            for (ReadWriteSpan span : spans) {
                spanTimeout.onStart(Context.root(), span);
            }

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(3);
            assertThat(spanTimeout.getSpansRejectedPerTraceTotal()).isEqualTo(2);
            assertThat(spanTimeout.getSpansRejectedGlobalTotal()).isZero();
            clock.set(Duration.ofMillis(20).toNanos());
            assertThat(spanTimeout.forceFlush().isSuccess()).isTrue();
            assertThat(spanTimeout.getForcedSpanEndAttemptsTotal()).isEqualTo(3);
            verify(spans.get(3), never()).end();
            verify(spans.get(4), never()).end();
            assertThat(spanTimeout.getActiveSpanCount()).isZero();

            ReadWriteSpan resumed = SpanTimeoutTestSpans.span(1, 6);
            spanTimeout.onStart(Context.root(), resumed);
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
            assertThat(spanTimeout.getSpansRejectedPerTraceTotal()).isEqualTo(2);
        }
    }
}
