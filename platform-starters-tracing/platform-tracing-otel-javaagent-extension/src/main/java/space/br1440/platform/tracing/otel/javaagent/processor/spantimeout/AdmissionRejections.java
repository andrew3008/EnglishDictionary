package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;

final class AdmissionRejections {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(AdmissionRejections.class);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Slot perTrace;
    private final Slot global;
    private final Slot traceCapacity;

    AdmissionRejections(int perTraceCapacity, int spanCapacity, int traceCapacity, LongSupplier nanoTime) {
        Objects.requireNonNull(nanoTime, "nanoTime");

        this.perTrace = new Slot(
                SpanTimeoutLogEvent.SPAN_TIMEOUT_PER_TRACE_CAPACITY_REACHED,
                perTraceCapacity,
                nanoTime
        );

        this.global = new Slot(
                SpanTimeoutLogEvent.SPAN_TIMEOUT_GLOBAL_SPAN_CAPACITY_REACHED,
                spanCapacity,
                nanoTime
        );

        this.traceCapacity = new Slot(
                SpanTimeoutLogEvent.SPAN_TIMEOUT_TRACKED_TRACE_CAPACITY_REACHED,
                traceCapacity,
                nanoTime
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

        log.log(slot.eventType, fields -> fields
                .putLong(AgentLogField.REJECTED, slot.total.get())
                .putLong(AgentLogField.LIMIT, slot.limit));
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

        private final AgentLogEventType eventType;
        private final int limit;
        private final AtomicLong total = new AtomicLong();
        private final MinIntervalRateLimiter limiter;

        private Slot(AgentLogEventType eventType, int limit, LongSupplier nanoTime) {
            this.eventType = eventType;
            this.limit = limit;
            this.limiter = new MinIntervalRateLimiter(LOG_INTERVAL_NANOS, nanoTime);
        }
    }
}
