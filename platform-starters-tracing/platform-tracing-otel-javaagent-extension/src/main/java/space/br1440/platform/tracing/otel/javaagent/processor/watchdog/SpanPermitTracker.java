package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

final class SpanPermitTracker {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanPermitTracker.class);

    private final int maxPermitCount;
    private final AtomicInteger acquiredPermitCount = new AtomicInteger();
    private final AtomicLong invariantViolationTotal = new AtomicLong();

    SpanPermitTracker(int maxPermitCount) {
        this.maxPermitCount = maxPermitCount;
    }

    boolean tryAcquire() {
        for (;;) {
            int current = acquiredPermitCount.get();
            if (current >= maxPermitCount) {
                return false;
            }

            if (acquiredPermitCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void release(int permitCount, String operationName, String traceId) {
        if (permitCount <= 0) {
            return;
        }

        for (;;) {
            int current = acquiredPermitCount.get();
            if (permitCount > current) {
                invariantViolationTotal.incrementAndGet();
                log.warn(AgentEventCodes.WATCHDOG_SPAN_PERMIT_INVARIANT_VIOLATION,
                        "Span permit release exceeded the acquired permit count",
                        "acquiredPermitCount", String.valueOf(current),
                        "requestedReleaseCount", String.valueOf(permitCount),
                        "operation", operationName,
                        "traceId", (traceId == null) ? "" : traceId);
                return;
            }

            if (acquiredPermitCount.compareAndSet(current, current - permitCount)) {
                return;
            }
        }
    }

    void recordInvariantViolation() {
        invariantViolationTotal.incrementAndGet();
    }

    int acquiredPermitCount() {
        return acquiredPermitCount.get();
    }

    int maxPermitCount() {
        return maxPermitCount;
    }

    long invariantViolationTotal() {
        return invariantViolationTotal.get();
    }
}
