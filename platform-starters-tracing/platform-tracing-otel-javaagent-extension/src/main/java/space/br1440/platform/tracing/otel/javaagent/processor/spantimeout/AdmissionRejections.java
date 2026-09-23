package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

final class AdmissionRejections {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(AdmissionRejections.class);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Slot perTrace;
    private final Slot global;
    private final Slot traceCapacity;

    AdmissionRejections(int perTraceCapacity, int spanCapacity, int traceCapacity, LongSupplier nanoTime) {
        Objects.requireNonNull(nanoTime, "nanoTime");

        this.perTrace = new Slot(
                "Span timeout rejected span tracking: per-trace tracked-span capacity reached",
                perTraceCapacity, nanoTime
        );

        this.global = new Slot(
                "Span timeout rejected span tracking: global tracked-span capacity reached",
                spanCapacity, nanoTime
        );

        this.traceCapacity = new Slot(
                "Span timeout rejected span tracking: tracked-trace capacity reached",
                traceCapacity, nanoTime
        );
    }

    void count(RejectionReason reason) {
        slot(reason).total.incrementAndGet();
    }

    void logIfAny(RejectionReason reason) {
        if (reason == null) {
            return;
        }

        Slot slot = slot(reason);
        if (!slot.limiter.tryAcquire()) {
            return;
        }

        log.warn(AgentEventCode.EXPORT_SPAN_TIMEOUT, slot.message,
                "rejected", String.valueOf(slot.total.get()),
                "limit", String.valueOf(slot.limit)
        );
    }

    long perTraceTotal() {
        return perTrace.total.get();
    }

    long globalTotal() {
        return global.total.get();
    }

    long traceCapacityTotal() {
        return traceCapacity.total.get();
    }

    private Slot slot(RejectionReason reason) {
        return switch (reason) {
            case PER_TRACE_CAPACITY -> perTrace;
            case GLOBAL_CAPACITY -> global;
            case TRACE_CAPACITY -> traceCapacity;
        };
    }

    enum RejectionReason {
        PER_TRACE_CAPACITY,
        GLOBAL_CAPACITY,
        TRACE_CAPACITY
    }

    private static final class Slot {

        private final String message;
        private final int limit;
        private final AtomicLong total = new AtomicLong();
        private final LogRateLimiter limiter;

        private Slot(String message, int limit, LongSupplier nanoTime) {
            this.message = message;
            this.limit = limit;
            this.limiter = new LogRateLimiter(LOG_INTERVAL_NANOS, nanoTime);
        }
    }
}
