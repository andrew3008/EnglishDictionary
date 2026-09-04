package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

class SpanPermitTrackerTest {

    @RegisterExtension
    final AgentLogCapture logCapture = new AgentLogCapture();

    @Test
    void tryAcquireRejectsAtCapacity() {
        SpanPermitTracker permitTracker = new SpanPermitTracker(3);

        assertThat(permitTracker.tryAcquire()).isTrue();
        assertThat(permitTracker.tryAcquire()).isTrue();
        assertThat(permitTracker.tryAcquire()).isTrue();
        assertThat(permitTracker.tryAcquire()).isFalse();

        assertThat(permitTracker.acquiredPermitCount()).isEqualTo(3);
        assertThat(permitTracker.maxPermitCount()).isEqualTo(3);
    }

    @Test
    void releaseRestoresAvailableCapacity() {
        SpanPermitTracker permitTracker = new SpanPermitTracker(2);
        assertThat(permitTracker.tryAcquire()).isTrue();
        assertThat(permitTracker.tryAcquire()).isTrue();

        permitTracker.release(1, "test-release", "trace-1");

        assertThat(permitTracker.acquiredPermitCount()).isEqualTo(1);
        assertThat(permitTracker.tryAcquire()).isTrue();
        assertThat(permitTracker.tryAcquire()).isFalse();
        assertThat(permitTracker.invariantViolationTotal()).isZero();
    }

    @Test
    void releaseIgnoresNonPositiveCount() {
        SpanPermitTracker permitTracker = new SpanPermitTracker(1);
        assertThat(permitTracker.tryAcquire()).isTrue();

        permitTracker.release(0, "test-release", null);
        permitTracker.release(-1, "test-release", null);

        assertThat(permitTracker.acquiredPermitCount()).isEqualTo(1);
        assertThat(permitTracker.invariantViolationTotal()).isZero();
    }

    @Test
    void releaseRejectsOverReleaseAndRecordsViolation() {
        SpanPermitTracker permitTracker = new SpanPermitTracker(5);
        assertThat(permitTracker.tryAcquire()).isTrue();

        // when
        permitTracker.release(3, "test-release", "trace-9");

        // then
        assertThat(permitTracker.acquiredPermitCount()).isEqualTo(1);
        assertThat(permitTracker.invariantViolationTotal()).isEqualTo(1);
        assertThat(logCapture.rendered())
                .contains(AgentEventCodes.WATCHDOG_SPAN_PERMIT_INVARIANT_VIOLATION)
                .contains("acquiredPermitCount")
                .contains("requestedReleaseCount")
                .contains("trace-9");
    }

    @Test
    void recordInvariantViolationIncrementsTotal() {
        SpanPermitTracker permitTracker = new SpanPermitTracker(1);

        permitTracker.recordInvariantViolation();
        permitTracker.recordInvariantViolation();

        assertThat(permitTracker.invariantViolationTotal()).isEqualTo(2);
        assertThat(permitTracker.acquiredPermitCount()).isZero();
    }

    @Test
    void concurrentAcquireDoesNotExceedCapacity() throws Exception {
        // given
        int maxPermitCount = 200;
        SpanPermitTracker permitTracker = new SpanPermitTracker(maxPermitCount);
        int threadCount = 8;
        int attemptsPerThread = 1_000;
        AtomicInteger acquiredPermitCount = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);

        // when
        try (ExecutorService executorService = Executors.newFixedThreadPool(threadCount)) {
            for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
                executorService.execute(() -> {
                    try {
                        startLatch.await();
                        for (int attemptIndex = 0; attemptIndex < attemptsPerThread; attemptIndex++) {
                            if (permitTracker.tryAcquire()) {
                                acquiredPermitCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            startLatch.countDown();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        // then
        assertThat(acquiredPermitCount.get()).isEqualTo(maxPermitCount);
        assertThat(permitTracker.acquiredPermitCount()).isEqualTo(maxPermitCount);
        assertThat(permitTracker.invariantViolationTotal()).isZero();
    }
}
