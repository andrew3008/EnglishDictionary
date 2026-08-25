package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

final class SpanPermitLedger {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanPermitLedger.class);

    private final int capacity;
    private final AtomicInteger trackedSpanCount = new AtomicInteger();
    private final AtomicLong invariantViolationTotal = new AtomicLong();

    SpanPermitLedger(int capacity) {
        this.capacity = capacity;
    }

    boolean tryAcquire() {
        for (;;) {
            int current = trackedSpanCount.get();
            if (current >= capacity) {
                return false;
            }

            if (trackedSpanCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void release(int count, String operation, String traceId) {
        if (count <= 0) {
            return;
        }

        for (;;) {
            int current = trackedSpanCount.get();
            if (count > current) {
                invariantViolationTotal.incrementAndGet();
                log.warn(AgentEventCodes.EXPORT_LEDGER_VIOLATION,
                        "Watchdog span ledger release exceeded tracked permits",
                        "ledgerCurrent", String.valueOf(current),
                        "requestedRelease", String.valueOf(count),
                        "operation", operation,
                        "traceId", (traceId == null) ? "" : traceId);
                return;
            }

            if (trackedSpanCount.compareAndSet(current, current - count)) {
                return;
            }
        }
    }

    void recordInvariantViolation() {
        invariantViolationTotal.incrementAndGet();
    }

    int trackedCount() {
        return trackedSpanCount.get();
    }

    int capacity() {
        return capacity;
    }

    long invariantViolationTotal() {
        return invariantViolationTotal.get();
    }
}
