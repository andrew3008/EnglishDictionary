package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanTimeoutGlobalBoundConcurrencyTest {

    @Test
    void concurrentStartsOnDifferentTracesNeverExceedGlobalCapacity() throws Exception {
        // given
        int capacity = 16;
        int threads = 8;
        int perThread = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger observedMax = new AtomicInteger();

        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 1_000, 1_000, capacity);
             ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            // when
            List<Future<?>> started = new ArrayList<>();
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                int baseTrace = (threadIndex * perThread) + 1;
                started.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int offset = 0; offset < perThread; offset++) {
                        ReadWriteSpan span = SpanTimeoutTestSpans.span(baseTrace + offset, 1);
                        spanTimeout.onStart(Context.root(), span);
                        int current = spanTimeout.getActiveSpanCount();
                        observedMax.accumulateAndGet(current, Math::max);
                        assertThat(current).isLessThanOrEqualTo(capacity);
                    }
                    return null;
                }));
            }

            for (Future<?> future : started) {
                future.get(10, TimeUnit.SECONDS);
            }

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isLessThanOrEqualTo(capacity);
            assertThat(spanTimeout.getActiveSpanCount() + spanTimeout.getSpansRejectedGlobalTotal())
                    .isEqualTo((long) threads * perThread);
            assertThat(spanTimeout.getSpansRejectedPerTraceTotal()).isZero();
            assertThat(spanTimeout.getSpanPermitInvariantViolationTotal()).isZero();
            assertThat(observedMax.get()).isLessThanOrEqualTo(capacity);
        }
    }
}
