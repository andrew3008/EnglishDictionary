package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;

final class WatchdogTraceIndex {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(WatchdogTraceIndex.class);
    private static final long EVICTION_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long NEVER_LOGGED_NANOS = Long.MIN_VALUE;
    private static final Runnable NO_EVICTION_PLAN_HOOK = () -> {
    };

    private static final int EMPTY_EVICTION_MIN_BATCH_DIVISOR = 10;
    private static final int MAX_EVICTION_PASSES = 3;

    private static final Comparator<EvictionCandidate> OLDEST_FIRST = Comparator
            .comparingLong(EvictionCandidate::startNanos)
            .thenComparing(EvictionCandidate::traceId);
    private static final Comparator<EvictionCandidate> NEWEST_FIRST = OLDEST_FIRST.reversed();

    private final long spanTimeoutNanos;
    private final long traceTimeoutNanos;
    private final int maxTrackedTraces;
    private final int maxTrackedSpansPerTrace;
    private final LongSupplier nanoTime;
    private final SpanPermitLedger ledger;
    private final Runnable afterFirstEvictionPlan;
    private final ConcurrentHashMap<String, TraceWatchdogState> traces = new ConcurrentHashMap<>();
    private final AtomicLong trackingEvictedTotal = new AtomicLong();
    private final AtomicLong liveTracesEvictedTotal = new AtomicLong();
    private final AtomicBoolean evicting = new AtomicBoolean();
    private final AtomicLong lastEvictionLogNanos = new AtomicLong(NEVER_LOGGED_NANOS);
    private final AtomicLong lastLiveEvictionLogNanos = new AtomicLong(NEVER_LOGGED_NANOS);
    private final AtomicLong spansRejectedPerTraceTotal = new AtomicLong();
    private final AtomicLong spansRejectedGlobalTotal = new AtomicLong();
    private final AtomicLong lastPerTraceRejectionLogNanos = new AtomicLong(NEVER_LOGGED_NANOS);
    private final AtomicLong lastGlobalRejectionLogNanos = new AtomicLong(NEVER_LOGGED_NANOS);

    WatchdogTraceIndex(long spanTimeoutNanos,
                       long traceTimeoutNanos,
                       int maxTrackedTraces,
                       int maxTrackedSpansPerTrace,
                       LongSupplier nanoTime,
                       SpanPermitLedger ledger) {
        this(spanTimeoutNanos, traceTimeoutNanos, maxTrackedTraces, maxTrackedSpansPerTrace, nanoTime, ledger, NO_EVICTION_PLAN_HOOK);
    }

    WatchdogTraceIndex(long spanTimeoutNanos,
                       long traceTimeoutNanos,
                       int maxTrackedTraces,
                       int maxTrackedSpansPerTrace,
                       LongSupplier nanoTime,
                       SpanPermitLedger ledger,
                       Runnable afterFirstEvictionPlan) {
        this.spanTimeoutNanos = spanTimeoutNanos;
        this.traceTimeoutNanos = traceTimeoutNanos;
        this.maxTrackedTraces = maxTrackedTraces;
        this.maxTrackedSpansPerTrace = maxTrackedSpansPerTrace;
        this.nanoTime = nanoTime;
        this.ledger = ledger;
        this.afterFirstEvictionPlan = afterFirstEvictionPlan;
    }

    void admit(String traceId, String spanId, ReadWriteSpan span, long now, BooleanSupplier admissionOpen) {
        RejectionReason[] rejectionHolder = new RejectionReason[1];
        traces.compute(traceId, (ignored, current) -> {
            if (!admissionOpen.getAsBoolean()) {
                return current;
            }

            boolean isNewTrace = (current == null);
            TraceWatchdogState state = isNewTrace ? new TraceWatchdogState(now) : current;

            if (state.contains(spanId)) {
                return state;
            }

            if (state.size() >= maxTrackedSpansPerTrace) {
                spansRejectedPerTraceTotal.incrementAndGet();
                rejectionHolder[0] = RejectionReason.PER_TRACE_CAPACITY;
                return state;
            }

            if (!ledger.tryAcquire()) {
                spansRejectedGlobalTotal.incrementAndGet();
                rejectionHolder[0] = RejectionReason.GLOBAL_CAPACITY;
                return isNewTrace ? null : state;
            }

            TraceWatchdogState.Tracked tracked = new TraceWatchdogState.Tracked(spanId, new WeakReference<>(span), now);
            if (!state.addIfAbsent(tracked)) {
                ledger.release(1, "onstart-defect", traceId);
                ledger.recordInvariantViolation();
            }

            return state;
        });

        logRejectionIfAny(rejectionHolder[0]);
    }

    void onEnd(String traceId, String spanId, ReadableSpan span) {
        traces.computeIfPresent(traceId, (ignored, state) -> {
            if (state.removeIfPresent(spanId, span)) {
                ledger.release(1, "on-end", traceId);
            }

            return state;
        });
    }

    void drainAll() {
        for (String traceId : traces.keySet()) {
            traces.computeIfPresent(traceId, (ignored, state) -> {
                int drained = state.drain();
                ledger.release(drained, "shutdown", traceId);
                return null;
            });
        }
    }

    List<ExpiredSpan> collectExpired(long now) {
        List<ExpiredSpan> timedOut = new ArrayList<>();
        for (String traceId : traces.keySet()) {
            traces.computeIfPresent(traceId, (ignored, state) -> {
                collectTimedOutSpans(traceId, state, now, timedOut);
                boolean epochExpired = (now - state.startNanos()) > traceTimeoutNanos;
                return (state.isEmpty() && epochExpired) ? null : state;
            });
        }

        auditSpanLedger();
        return timedOut;
    }

    void evictOldestIfNeeded() {
        if (traces.size() <= maxTrackedTraces) {
            return;
        }

        if (!evicting.compareAndSet(false, true)) {
            return;
        }

        try {
            evictOldestBatch();
        } finally {
            evicting.set(false);
        }
    }

    int size() {
        return traces.size();
    }

    int nonEmptyTraceCount() {
        int count = 0;
        for (TraceWatchdogState state : traces.values()) {
            if (!state.isEmpty()) {
                count++;
            }
        }

        return count;
    }

    int traceCapacity() {
        return maxTrackedTraces;
    }

    int perTraceCapacity() {
        return maxTrackedSpansPerTrace;
    }

    int spanCapacity() {
        return ledger.capacity();
    }

    int trackedSpanCount() {
        return ledger.trackedCount();
    }

    long trackingEvictedTotal() {
        return trackingEvictedTotal.get();
    }

    long liveTracesEvictedTotal() {
        return liveTracesEvictedTotal.get();
    }

    long rejectedPerTraceTotal() {
        return spansRejectedPerTraceTotal.get();
    }

    long rejectedGlobalTotal() {
        return spansRejectedGlobalTotal.get();
    }

    long ledgerInvariantViolationTotal() {
        return ledger.invariantViolationTotal();
    }

    private void collectTimedOutSpans(String traceId,
                                      TraceWatchdogState state,
                                      long now,
                                      List<ExpiredSpan> timedOut) {
        boolean traceExpired = (now - state.startNanos()) > traceTimeoutNanos;
        for (TraceWatchdogState.Tracked tracked : state.records()) {
            ReadWriteSpan span = tracked.spanRef().get();
            if (span == null || span.hasEnded()) {
                if (state.remove(tracked)) {
                    ledger.release(1, "scan-purge", traceId);
                }

                continue;
            }

            boolean spanExpired = (now - tracked.startNanos()) > spanTimeoutNanos;
            if ((spanExpired || traceExpired) && state.remove(tracked)) {
                ledger.release(1, "scan-timeout", traceId);

                ExpiredSpan expiredSpan = new ExpiredSpan(
                        span,
                        traceId,
                        spanExpired ? TimeoutReason.SPAN : TimeoutReason.TRACE
                );
                timedOut.add(expiredSpan);
            }
        }
    }

    private void auditSpanLedger() {
        if (!log.isDebugEnabled()) {
            return;
        }

        int observedSum = 0;
        for (TraceWatchdogState state : traces.values()) {
            observedSum += state.size();
        }

        int ledgerValue = ledger.trackedCount();
        if (observedSum != ledgerValue) {
            log.debug(AgentEventCodes.EXPORT_WATCHDOG,
                    "Watchdog span ledger audit observed a difference that is legal under concurrency",
                    "ledger", String.valueOf(ledgerValue),
                    "observedSum", String.valueOf(observedSum)
            );
        }
    }

    private void logRejectionIfAny(RejectionReason reason) {
        if (reason == null) {
            return;
        }

        if (reason == RejectionReason.PER_TRACE_CAPACITY) {
            logRejectionRateLimited(
                    lastPerTraceRejectionLogNanos,
                    "Watchdog rejected span registration: per-trace tracked-span capacity reached",
                    spansRejectedPerTraceTotal.get(),
                    maxTrackedSpansPerTrace
            );
        } else {
            logRejectionRateLimited(
                    lastGlobalRejectionLogNanos,
                    "Watchdog rejected span registration: global tracked-span capacity reached",
                    spansRejectedGlobalTotal.get(),
                    ledger.capacity()
            );
        }
    }

    private void logRejectionRateLimited(AtomicLong lastLogNanos, String message, long rejectedTotal, int limit) {
        long now = nanoTime.getAsLong();
        long previous = lastLogNanos.get();
        if ((previous != NEVER_LOGGED_NANOS) && ((now - previous) < EVICTION_LOG_INTERVAL_NANOS)) {
            return;
        }

        if (!lastLogNanos.compareAndSet(previous, now)) {
            return;
        }

        log.warn(AgentEventCodes.EXPORT_WATCHDOG, message,
                "rejected", String.valueOf(rejectedTotal),
                "limit", String.valueOf(limit)
        );
    }

    private void evictOldestBatch() {
        int evictedEmpty = 0;
        int evictedLive = 0;
        int releasedPermits = 0;

        for (int pass = 1; pass <= MAX_EVICTION_PASSES; pass++) {
            int size = traces.size();
            if (size <= maxTrackedTraces) {
                break;
            }

            List<EvictionCandidate> plan = planEviction(size);
            if (plan.isEmpty()) {
                break;
            }

            if (pass == 1) {
                afterFirstEvictionPlan.run();
            }

            EvictionPassOutcome outcome = commitEvictionPlan(plan);
            ledger.release(outcome.releasedSpanPermits(), "eviction", null);
            trackingEvictedTotal.addAndGet(outcome.evictedTotal());
            liveTracesEvictedTotal.addAndGet(outcome.evictedLive());

            evictedEmpty += outcome.evictedEmpty();
            evictedLive += outcome.evictedLive();
            releasedPermits += outcome.releasedSpanPermits();
        }

        logEvictionRun(evictedEmpty, evictedLive, releasedPermits);
    }

    private EvictionPassOutcome commitEvictionPlan(List<EvictionCandidate> plan) {
        int evictedEmpty = 0;
        int evictedLive = 0;
        int releasedPermits = 0;

        for (EvictionCandidate candidate : plan) {
            int[] drainedHolder = new int[1];
            boolean[] removedHolder = new boolean[1];
            traces.computeIfPresent(candidate.traceId(), (ignored, current) -> {
                if (current != candidate.expectedState()) {
                    return current;
                }

                if ((candidate.kind() == CandidateKind.EMPTY) && !current.isEmpty()) {
                    return current;
                }

                removedHolder[0] = true;
                drainedHolder[0] = current.drain();
                return null;
            });

            if (!removedHolder[0]) {
                continue;
            }

            if (drainedHolder[0] > 0) {
                evictedLive++;
                releasedPermits += drainedHolder[0];
            } else {
                evictedEmpty++;
            }
        }

        return new EvictionPassOutcome(evictedEmpty, evictedLive, releasedPermits);
    }

    private List<EvictionCandidate> planEviction(int size) {
        int overflow = (size - maxTrackedTraces);
        int emptyBatch = Math.max(overflow, Math.max(1, maxTrackedTraces / EMPTY_EVICTION_MIN_BATCH_DIVISOR));
        emptyBatch = Math.min(emptyBatch, size);

        PriorityQueue<EvictionCandidate> empty = new PriorityQueue<>(emptyBatch, NEWEST_FIRST);
        PriorityQueue<EvictionCandidate> nonEmpty = new PriorityQueue<>(Math.max(1, overflow), NEWEST_FIRST);

        for (Map.Entry<String, TraceWatchdogState> entry : traces.entrySet()) {
            TraceWatchdogState state = entry.getValue();
            boolean isEmpty = state.isEmpty();
            EvictionCandidate candidate = new EvictionCandidate(
                    entry.getKey(),
                    state,
                    state.startNanos(),
                    isEmpty ? CandidateKind.EMPTY : CandidateKind.LIVE
            );

            if (isEmpty) {
                offerBounded(empty, candidate, emptyBatch);
            } else {
                offerBounded(nonEmpty, candidate, overflow);
            }
        }

        int neededFromNonEmpty = Math.max(0, overflow - empty.size());
        while (nonEmpty.size() > neededFromNonEmpty) {
            nonEmpty.poll();
        }

        List<EvictionCandidate> plan = new ArrayList<>(empty.size() + nonEmpty.size());
        plan.addAll(empty);
        plan.addAll(nonEmpty);
        return List.copyOf(plan);
    }

    private static void offerBounded(PriorityQueue<EvictionCandidate> queue, EvictionCandidate candidate, int batch) {
        if (queue.size() < batch) {
            queue.offer(candidate);
            return;
        }

        EvictionCandidate newestKept = queue.peek();
        if ((newestKept != null) && (OLDEST_FIRST.compare(candidate, newestKept) < 0)) {
            queue.poll();
            queue.offer(candidate);
        }
    }

    private void logEvictionRun(int evictedEmpty, int evictedLive, int releasedPermits) {
        int evictedTotal = (evictedEmpty + evictedLive);
        if ((evictedTotal > 0) && acquireLogSlot(lastEvictionLogNanos)) {
            log.warn(AgentEventCodes.EXPORT_WATCHDOG, "Watchdog evicted oldest tracked traces",
                    "evicted", String.valueOf(evictedTotal),
                    "capacity", String.valueOf(maxTrackedTraces),
                    "releasedSpanPermits", String.valueOf(releasedPermits));
        }

        if ((releasedPermits > 0) && acquireLogSlot(lastLiveEvictionLogNanos)) {
            log.warn(AgentEventCodes.EXPORT_WATCHDOG_LIVE_EVICTION,
                    "Watchdog evicted traces that still had tracked spans",
                    "evictedLive", String.valueOf(evictedLive),
                    "releasedSpanPermits", String.valueOf(releasedPermits),
                    "liveTracesEvictedTotal", String.valueOf(liveTracesEvictedTotal.get()),
                    "capacity", String.valueOf(maxTrackedTraces));
        }
    }

    private boolean acquireLogSlot(AtomicLong lastLogNanos) {
        long now = nanoTime.getAsLong();
        long previous = lastLogNanos.get();
        if ((previous != NEVER_LOGGED_NANOS) && ((now - previous) < EVICTION_LOG_INTERVAL_NANOS)) {
            return false;
        }

        return lastLogNanos.compareAndSet(previous, now);
    }

    private enum CandidateKind {
        EMPTY,
        LIVE
    }

    private record EvictionCandidate(String traceId,
                                     TraceWatchdogState expectedState,
                                     long startNanos,
                                     CandidateKind kind) {
    }

    private record EvictionPassOutcome(int evictedEmpty, int evictedLive, int releasedSpanPermits) {

        int evictedTotal() {
            return (evictedEmpty + evictedLive);
        }
    }

    enum RejectionReason {
        PER_TRACE_CAPACITY,
        GLOBAL_CAPACITY
    }

    enum TimeoutReason {
        SPAN("span"),
        TRACE("trace");

        private final String value;

        TimeoutReason(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    record ExpiredSpan(ReadWriteSpan span, String traceId, TimeoutReason reason) {
    }
}
