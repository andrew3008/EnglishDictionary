package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

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

class SpanWatchdogGlobalBoundConcurrencyTest {

    @Test
    void concurrentStartsOnDifferentTracesNeverExceedGlobalCapacity() throws Exception {
        // given
        int capacity = 16;
        int threads = 8;
        int perThread = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger observedMax = new AtomicInteger();

        try (SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 1_000, 1_000, capacity);
             ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            // when
            List<Future<?>> started = new ArrayList<>();
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                int baseTrace = (threadIndex * perThread) + 1;
                started.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int offset = 0; offset < perThread; offset++) {
                        ReadWriteSpan span = WatchdogTestSpans.span(baseTrace + offset, 1);
                        watchdog.onStart(Context.root(), span);
                        int current = watchdog.getActiveSpanCount();
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
            assertThat(watchdog.getActiveSpanCount()).isLessThanOrEqualTo(capacity);
            assertThat(watchdog.getActiveSpanCount() + watchdog.getSpansRejectedGlobalTotal())
                    .isEqualTo((long) threads * perThread);
            assertThat(watchdog.getSpansRejectedPerTraceTotal()).isZero();
            assertThat(watchdog.getSpanPermitInvariantViolationTotal()).isZero();
            assertThat(observedMax.get()).isLessThanOrEqualTo(capacity);
        }
    }
}
