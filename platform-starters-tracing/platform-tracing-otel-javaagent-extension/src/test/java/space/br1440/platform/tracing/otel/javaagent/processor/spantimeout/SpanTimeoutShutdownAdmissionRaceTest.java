package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanTimeoutShutdownAdmissionRaceTest {

    @Test
    void onStartAfterFastPathDoesNotAdmitOnceShutdownCompletedDrain() throws Exception {
        // given
        CountDownLatch passedFastPath = new CountDownLatch(1);
        CountDownLatch releaseCompute = new CountDownLatch(1);
        ReadWriteSpan span = SpanTimeoutTestSpans.span(1, 1);

        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10,
                () -> {
                    passedFastPath.countDown();

                    try {
                        assertThat(releaseCompute.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });
             ExecutorService executor = Executors.newSingleThreadExecutor()) {

            // when
            Future<?> start = executor.submit(() -> spanTimeout.onStart(Context.root(), span));
            assertThat(passedFastPath.await(5, TimeUnit.SECONDS)).isTrue();
            spanTimeout.shutdown();
            releaseCompute.countDown();
            start.get(5, TimeUnit.SECONDS);

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getTrackedTraceCount()).isZero();
            assertThat(spanTimeout.getSpansRejectedGlobalTotal()).isZero();
            assertThat(spanTimeout.getSpanPermitInvariantViolationTotal()).isZero();
        }
    }
}
