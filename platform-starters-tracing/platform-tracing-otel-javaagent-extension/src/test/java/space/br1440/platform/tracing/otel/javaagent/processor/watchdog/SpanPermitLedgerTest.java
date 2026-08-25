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

/** Прямая характеризация CAS-бюджета {@link SpanPermitLedger}, без прохода через фасад. */
class SpanPermitLedgerTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void tryAcquireStopsExactlyAtCapacity() {
        SpanPermitLedger ledger = new SpanPermitLedger(3);

        assertThat(ledger.tryAcquire()).isTrue();
        assertThat(ledger.tryAcquire()).isTrue();
        assertThat(ledger.tryAcquire()).isTrue();
        assertThat(ledger.tryAcquire()).isFalse();

        assertThat(ledger.trackedCount()).isEqualTo(3);
        assertThat(ledger.capacity()).isEqualTo(3);
    }

    @Test
    void releaseReopensExactlyTheReleasedCapacity() {
        SpanPermitLedger ledger = new SpanPermitLedger(2);
        assertThat(ledger.tryAcquire()).isTrue();
        assertThat(ledger.tryAcquire()).isTrue();

        ledger.release(1, "test-release", "trace-1");

        assertThat(ledger.trackedCount()).isEqualTo(1);
        assertThat(ledger.tryAcquire()).isTrue();
        assertThat(ledger.tryAcquire()).isFalse();
        assertThat(ledger.invariantViolationTotal()).isZero();
    }

    @Test
    void releaseWithNonPositiveCountIsNoOp() {
        SpanPermitLedger ledger = new SpanPermitLedger(1);
        assertThat(ledger.tryAcquire()).isTrue();

        ledger.release(0, "test-release", null);
        ledger.release(-1, "test-release", null);

        assertThat(ledger.trackedCount()).isEqualTo(1);
        assertThat(ledger.invariantViolationTotal()).isZero();
    }

    @Test
    void releaseBeyondTrackedCountIsRejectedAndCountedAsInvariantViolation() {
        SpanPermitLedger ledger = new SpanPermitLedger(5);
        assertThat(ledger.tryAcquire()).isTrue();

        // when: release запрашивает больше, чем реально выдано (underflow)
        ledger.release(3, "test-release", "trace-9");

        // then: ёмкость не открывается сверх фактически выданных permit'ов
        assertThat(ledger.trackedCount()).isEqualTo(1);
        assertThat(ledger.invariantViolationTotal()).isEqualTo(1);
        assertThat(logs.rendered())
                .contains(AgentEventCodes.EXPORT_LEDGER_VIOLATION)
                .contains("ledgerCurrent")
                .contains("requestedRelease")
                .contains("trace-9");
    }

    @Test
    void recordInvariantViolationIncrementsIndependentlyOfRelease() {
        SpanPermitLedger ledger = new SpanPermitLedger(1);

        ledger.recordInvariantViolation();
        ledger.recordInvariantViolation();

        assertThat(ledger.invariantViolationTotal()).isEqualTo(2);
        assertThat(ledger.trackedCount()).isZero();
    }

    @Test
    void concurrentAcquireNeverGrantsMoreThanCapacity() throws Exception {
        // given
        int capacity = 200;
        SpanPermitLedger ledger = new SpanPermitLedger(capacity);
        int threads = 8;
        int attemptsPerThread = 1_000;
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // when
        try {
            for (int thread = 0; thread < threads; thread++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int attempt = 0; attempt < attemptsPerThread; attempt++) {
                            if (ledger.tryAcquire()) {
                                granted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(granted.get()).isEqualTo(capacity);
        assertThat(ledger.trackedCount()).isEqualTo(capacity);
        assertThat(ledger.invariantViolationTotal()).isZero();
    }
}
