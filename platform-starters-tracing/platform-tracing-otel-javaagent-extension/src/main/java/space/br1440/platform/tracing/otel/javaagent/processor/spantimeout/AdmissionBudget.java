package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

final class AdmissionBudget {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(AdmissionBudget.class);

    private final String kind;
    private final int capacity;
    private final AtomicInteger used = new AtomicInteger();
    private final AtomicLong invariantViolationTotal = new AtomicLong();

    AdmissionBudget(String kind, int capacity) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.capacity = capacity;
    }

    boolean tryAcquire() {
        for (;;) {
            int current = used.get();
            if (current >= capacity) {
                return false;
            }

            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void release(int permitCount, String operationName, String traceId) {
        if (permitCount <= 0) {
            return;
        }

        for (;;) {
            int current = used.get();
            if (permitCount > current) {
                invariantViolationTotal.incrementAndGet();
                log.warn(AgentEventCode.EXPORT_SPAN_TIMEOUT_PERMIT_VIOLATION,
                        "Permit release exceeded the acquired permit count",
                        "kind", kind,
                        "acquiredPermitCount", String.valueOf(current),
                        "requestedReleaseCount", String.valueOf(permitCount),
                        "operation", operationName,
                        "traceId", (traceId == null) ? "" : traceId
                );

                return;
            }

            if (used.compareAndSet(current, current - permitCount)) {
                return;
            }
        }
    }

    void recordInvariantViolation() {
        invariantViolationTotal.incrementAndGet();
    }

    boolean isFull() {
        return (used.get() >= capacity);
    }

    int used() {
        return used.get();
    }

    int capacity() {
        return capacity;
    }

    long invariantViolationTotal() {
        return invariantViolationTotal.get();
    }
}
