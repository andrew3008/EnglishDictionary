package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.MinIntervalRateLimiter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.AdmissionRejections.RejectionReason;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.EmptyEvictionPlanner.EvictionCandidate;

import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

final class InFlightTraceIndex {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(InFlightTraceIndex.class);
    private static final long EVICTION_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Runnable NO_EVICTION_PLAN_HOOK = () -> {
    };

    private static final int EMPTY_EVICTION_BATCH_DIVISOR = 10;
    private static final int MAX_EVICTION_PASSES = 3;

    private final long spanTimeoutNanos;
    private final long traceTimeoutNanos;
    private final int maxTrackedSpansPerTrace;
    private final AdmissionBudget spanBudget;
    private final AdmissionBudget traceBudget;
    private final Runnable afterFirstEvictionPlan;
    private final ConcurrentHashMap<String, InFlightTraceState> traces = new ConcurrentHashMap<>();
    private final AtomicBoolean pressure = new AtomicBoolean();
    private final AtomicBoolean evicting = new AtomicBoolean();
    private final AtomicLong trackingEvictedTotal = new AtomicLong();
    private final MinIntervalRateLimiter evictionLog;
    private final AdmissionRejections rejections;

    InFlightTraceIndex(long spanTimeoutNanos,
                       long traceTimeoutNanos,
                       int maxTrackedTraces,
                       int maxTrackedSpansPerTrace,
                       int maxTrackedSpans,
                       LongSupplier nanoTime) {
        this(spanTimeoutNanos, traceTimeoutNanos, maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans,
                nanoTime, NO_EVICTION_PLAN_HOOK);
    }

    InFlightTraceIndex(long spanTimeoutNanos,
                       long traceTimeoutNanos,
                       int maxTrackedTraces,
                       int maxTrackedSpansPerTrace,
                       int maxTrackedSpans,
                       LongSupplier nanoTime,
                       Runnable afterFirstEvictionPlan) {
        this.spanTimeoutNanos = spanTimeoutNanos;
        this.traceTimeoutNanos = traceTimeoutNanos;
        this.maxTrackedSpansPerTrace = maxTrackedSpansPerTrace;
        this.spanBudget = new AdmissionBudget("span", maxTrackedSpans);
        this.traceBudget = new AdmissionBudget("trace", maxTrackedTraces);
        this.afterFirstEvictionPlan = afterFirstEvictionPlan;
        this.evictionLog = new MinIntervalRateLimiter(EVICTION_LOG_INTERVAL_NANOS, nanoTime);
        this.rejections = new AdmissionRejections(maxTrackedSpansPerTrace, maxTrackedSpans, maxTrackedTraces, nanoTime);
    }

    void admit(String traceId, String spanId, ReadWriteSpan span, long now, BooleanSupplier admissionOpen) {
        AdmissionOutcome outcome = new AdmissionOutcome();
        traces.compute(traceId, (ignored, current) -> {
            if (!admissionOpen.getAsBoolean()) {
                return current;
            }

            if (current == null) {
                return admitNewTrace(traceId, spanId, span, now, outcome);
            }

            trackSpan(current, traceId, spanId, span, now, outcome);
            return current;
        });

        rejections.logIfAny(outcome.rejection);
    }

    private InFlightTraceState admitNewTrace(String traceId,
                                             String spanId,
                                             ReadWriteSpan span,
                                             long now,
                                             AdmissionOutcome outcome) {
        if (!traceBudget.tryAcquire()) {
            recordRejection(RejectionReason.TRACE_CAPACITY, outcome);
            return null;
        }

        try {
            InFlightTraceState state = new InFlightTraceState(now);
            if (trackSpan(state, traceId, spanId, span, now, outcome)) {
                return state;
            }

            traceBudget.release(1, "admit-rollback", traceId);
            return null;
        } catch (Throwable failure) {
            traceBudget.release(1, "admit-failure", traceId);
            throw failure;
        }
    }

    private boolean trackSpan(InFlightTraceState state,
                              String traceId,
                              String spanId,
                              ReadWriteSpan span,
                              long now,
                              AdmissionOutcome outcome) {
        if (state.contains(spanId)) {
            return true;
        }

        if (state.size() >= maxTrackedSpansPerTrace) {
            recordRejection(RejectionReason.PER_TRACE_CAPACITY, outcome);
            return false;
        }

        if (!spanBudget.tryAcquire()) {
            recordRejection(RejectionReason.GLOBAL_CAPACITY, outcome);
            return false;
        }

        boolean added;
        try {
            added = state.addIfAbsent(new InFlightTraceState.Tracked(spanId, span, now));
        } catch (Throwable failure) {
            spanBudget.release(1, "admit-failure", traceId);
            throw failure;
        }

        if (!added) {
            spanBudget.release(1, "onstart-defect", traceId);
            spanBudget.recordInvariantViolation();
        }

        return added;
    }

    private void recordRejection(RejectionReason reason, AdmissionOutcome outcome) {
        outcome.rejection = reason;
        rejections.count(reason);

        if (reason == RejectionReason.TRACE_CAPACITY) {
            pressure.set(true);
        }
    }

    void onEnd(String traceId, String spanId, ReadableSpan span) {
        boolean[] removedHolder = new boolean[1];
        traces.computeIfPresent(traceId, (ignored, state) -> {
            removedHolder[0] = state.removeIfPresent(spanId, span);
            return state;
        });

        if (removedHolder[0]) {
            spanBudget.release(1, "on-end", traceId);
        }
    }

    void drainAll() {
        for (String traceId : traces.keySet()) {
            int[] drainedHolder = new int[1];
            boolean[] removedHolder = new boolean[1];
            traces.computeIfPresent(traceId, (ignored, state) -> {
                drainedHolder[0] = state.drain();
                removedHolder[0] = true;
                return null;
            });

            spanBudget.release(drainedHolder[0], "shutdown", traceId);
            if (removedHolder[0]) {
                traceBudget.release(1, "shutdown", traceId);
            }
        }
    }

    void collectExpired(long now, Deque<ExpiredSpan> into) {
        for (String traceId : traces.keySet()) {
            int[] purgedHolder = new int[1];
            boolean[] removedHolder = new boolean[1];
            traces.computeIfPresent(traceId, (ignored, state) -> {
                boolean epochExpired = traceEpochExpired(state, now);
                purgedHolder[0] = collectTimedOutSpans(traceId, state, now, epochExpired, into);
                if (state.isEmpty() && epochExpired) {
                    removedHolder[0] = true;
                    return null;
                }

                return state;
            });

            spanBudget.release(purgedHolder[0], "scan-purge", traceId);
            if (removedHolder[0]) {
                traceBudget.release(1, "epoch-expired", traceId);
            }
        }
    }

    void releaseCandidatePermit(ExpiredSpan candidate, String operationName) {
        spanBudget.release(1, operationName, candidate.traceId());
    }

    void relieveTracePressure() {
        boolean pressureObserved = pressure.getAndSet(false);
        if (!traceBudget.isFull() && !pressureObserved) {
            return;
        }

        if (!evicting.compareAndSet(false, true)) {
            return;
        }

        try {
            evictEmptyStates();
        } finally {
            evicting.set(false);
        }
    }

    int size() {
        return traces.size();
    }

    int nonEmptyTraceCount() {
        int count = 0;
        for (InFlightTraceState state : traces.values()) {
            if (!state.isEmpty()) {
                count++;
            }
        }

        return count;
    }

    int traceCapacity() {
        return traceBudget.capacity();
    }

    int acquiredTraceSlotCount() {
        return traceBudget.used();
    }

    int perTraceCapacity() {
        return maxTrackedSpansPerTrace;
    }

    int spanCapacity() {
        return spanBudget.capacity();
    }

    int trackedSpanCount() {
        return spanBudget.used();
    }

    long trackingEvictedTotal() {
        return trackingEvictedTotal.get();
    }

    long rejectedPerTraceTotal() {
        return rejections.perTraceTotal();
    }

    long rejectedGlobalTotal() {
        return rejections.globalTotal();
    }

    long rejectedTraceCapacityTotal() {
        return rejections.traceCapacityTotal();
    }

    long permitInvariantViolationTotal() {
        return spanBudget.invariantViolationTotal();
    }

    long traceSlotInvariantViolationTotal() {
        return traceBudget.invariantViolationTotal();
    }

    private int collectTimedOutSpans(String traceId,
                                     InFlightTraceState state,
                                     long now,
                                     boolean traceExpired,
                                     Deque<ExpiredSpan> into) {
        int purged = 0;
        for (InFlightTraceState.Tracked tracked : state.records()) {
            ReadWriteSpan span = tracked.span();
            if (span.hasEnded()) {
                if (state.remove(tracked)) {
                    purged++;
                }

                continue;
            }

            boolean spanExpired = spanExpired(tracked, now);
            if ((spanExpired || traceExpired) && state.remove(tracked)) {
                into.add(new ExpiredSpan(
                        span,
                        traceId,
                        tracked.spanId(),
                        spanExpired ? TimeoutReason.SPAN : TimeoutReason.TRACE
                ));
            }
        }

        return purged;
    }

    private boolean traceEpochExpired(InFlightTraceState state, long now) {
        return (now - state.startNanos()) > traceTimeoutNanos;
    }

    private boolean spanExpired(InFlightTraceState.Tracked tracked, long now) {
        return (now - tracked.startNanos()) > spanTimeoutNanos;
    }

    private void evictEmptyStates() {
        int evicted = 0;
        int batch = Math.max(1, traceBudget.capacity() / EMPTY_EVICTION_BATCH_DIVISOR);

        for (int pass = 1; pass <= MAX_EVICTION_PASSES; pass++) {
            List<EvictionCandidate> plan = EmptyEvictionPlanner.selectOldestEmpty(traces.entrySet(), batch);
            if (plan.isEmpty()) {
                break;
            }

            if (pass == 1) {
                afterFirstEvictionPlan.run();
            }

            int committed = commitEvictionPlan(plan);
            evicted += committed;
            if (committed == plan.size()) {
                break;
            }
        }

        if (evicted > 0) {
            trackingEvictedTotal.addAndGet(evicted);
            logEvictionRun(evicted);
        }
    }

    private int commitEvictionPlan(List<EvictionCandidate> plan) {
        int evicted = 0;

        for (EvictionCandidate candidate : plan) {
            boolean[] removedHolder = new boolean[1];
            traces.computeIfPresent(candidate.traceId(), (ignored, current) -> {
                if ((current != candidate.expectedState()) || !current.isEmpty()) {
                    return current;
                }

                removedHolder[0] = true;
                return null;
            });

            if (removedHolder[0]) {
                traceBudget.release(1, "eviction", candidate.traceId());
                evicted++;
            }
        }

        return evicted;
    }

    private void logEvictionRun(int evicted) {
        if (!evictionLog.tryAcquire()) {
            return;
        }

        log.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_EMPTY_TRACE_STATES_EVICTED, fields -> fields
                .putLong(AgentLogField.EVICTED, evicted)
                .putLong(AgentLogField.CAPACITY, traceBudget.capacity()));
    }

    private static final class AdmissionOutcome {

        private RejectionReason rejection;
    }
}
