package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class AdmissionBudgetTest {

    @RegisterExtension
    final AgentLogCapture logCapture = new AgentLogCapture();

    @Test
    void tryAcquireRejectsAtCapacity() {
        // given
        AdmissionBudget budget = new AdmissionBudget("span", 3);

        // when/then
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();

        assertThat(budget.used()).isEqualTo(3);
        assertThat(budget.capacity()).isEqualTo(3);
    }

    @Test
    void releaseRestoresAvailableCapacity() {
        // given
        AdmissionBudget budget = new AdmissionBudget("span", 2);
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();

        // when
        budget.release(1, "test-release", "trace-1");

        // then
        assertThat(budget.used()).isEqualTo(1);
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.invariantViolationTotal()).isZero();
    }

    @Test
    void releaseIgnoresNonPositiveCount() {
        // given
        AdmissionBudget budget = new AdmissionBudget("span", 1);
        assertThat(budget.tryAcquire()).isTrue();

        // when
        budget.release(0, "test-release", null);
        budget.release(-1, "test-release", null);

        // then
        assertThat(budget.used()).isEqualTo(1);
        assertThat(budget.invariantViolationTotal()).isZero();
    }

    @Test
    void releaseRejectsOverReleaseAndRecordsViolation() {
        AdmissionBudget budget = new AdmissionBudget("span", 5);
        assertThat(budget.tryAcquire()).isTrue();

        // when
        budget.release(3, "test-release", "trace-9");

        // then
        assertThat(budget.used()).isEqualTo(1);
        assertThat(budget.invariantViolationTotal()).isEqualTo(1);
        assertThat(logCapture.rendered())
                .contains(AgentEventCode.EXPORT_SPAN_TIMEOUT_PERMIT_VIOLATION.code())
                .contains("acquiredPermitCount")
                .contains("requestedReleaseCount")
                .contains("trace-9");
    }

    @Test
    void recordInvariantViolationIncrementsTotal() {
        // given
        AdmissionBudget budget = new AdmissionBudget("span", 1);

        // when
        budget.recordInvariantViolation();
        budget.recordInvariantViolation();

        // then
        assertThat(budget.invariantViolationTotal()).isEqualTo(2);
        assertThat(budget.used()).isZero();
    }

    @Test
    void concurrentAcquireDoesNotExceedCapacity() throws Exception {
        // given
        int capacity = 200;
        AdmissionBudget budget = new AdmissionBudget("span", capacity);
        int threadCount = 8;
        int attemptsPerThread = 1_000;
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);

        // when
        try (ExecutorService executorService = Executors.newFixedThreadPool(threadCount)) {
            for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
                executorService.execute(() -> {
                    try {
                        startLatch.await();
                        for (int attemptIndex = 0; attemptIndex < attemptsPerThread; attemptIndex++) {
                            if (budget.tryAcquire()) {
                                acquired.incrementAndGet();
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
        assertThat(acquired.get()).isEqualTo(capacity);
        assertThat(budget.used()).isEqualTo(capacity);
        assertThat(budget.invariantViolationTotal()).isZero();
    }
}
