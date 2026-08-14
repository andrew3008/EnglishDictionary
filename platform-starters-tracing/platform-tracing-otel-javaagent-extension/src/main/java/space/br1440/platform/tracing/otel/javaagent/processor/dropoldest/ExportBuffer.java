package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;

final class ExportBuffer {

    private static final long MIN_WORKER_AWAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final DropOldestExportMetrics metrics;
    private final SpanQueue spanQueue;
    private final FlushTracker flushTracker = new FlushTracker();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();

    private volatile LifecycleState lifecycleState = LifecycleState.ACTIVE;
    private InFlightExport inFlightExport;

    ExportBuffer(int maxQueueSize, int maxExportBatchSize, DropOldestExportMetrics metrics) {
        this.spanQueue = new SpanQueue(maxQueueSize, maxExportBatchSize);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    boolean isAdmissionOpen() {
        return (lifecycleState == LifecycleState.ACTIVE);
    }

    void rejectAdmission() {
        if (lifecycleState == LifecycleState.FAILED) {
            metrics.incrementDroppedAfterFailure();
        } else {
            metrics.incrementDroppedAfterShutdown();
        }
    }

    void admit(SpanData snapshot) {
        lock.lock();
        try {
            if (lifecycleState != LifecycleState.ACTIVE) {
                rejectAdmission();
                return;
            }

            SpanQueue.AdmitOutcome outcome = spanQueue.admit(snapshot);
            if (outcome.dropped() != null) {
                flushTracker.markFailureAtOrBefore(outcome.dropped().sequence());
                metrics.incrementDroppedOverflow();
            }

            if (spanQueue.hasBatchReady() || flushTracker.hasActive()) {
                workAvailable.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    WorkUnit awaitWork(long scheduleDelayNanos, long lastExportNanos) {
        List<FlushTracker.FlushCompletion> completions = new ArrayList<>(2);
        WorkUnit result;

        lock.lock();
        try {
            completions.addAll(collectTerminalFlushesLocked());
            if (isWorkerDoneLocked()) {
                result = WorkUnit.terminate();
            } else {
                long elapsedSinceExport = System.nanoTime() - lastExportNanos;
                WorkUnit loopResult = null;
                while (!shouldExportNowLocked(scheduleDelayNanos, elapsedSinceExport)) {
                    if (isWorkerDoneLocked()) {
                        loopResult = WorkUnit.terminate();
                        break;
                    }

                    long waitNanos = spanQueue.isEmpty()
                            ? scheduleDelayNanos
                            : Math.max(MIN_WORKER_AWAIT_NANOS, scheduleDelayNanos - elapsedSinceExport);
                    try {
                        workAvailable.awaitNanos(waitNanos);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        completions.addAll(failTerminalLocked());
                        loopResult = WorkUnit.terminate();
                        break;
                    }

                    elapsedSinceExport = System.nanoTime() - lastExportNanos;
                }

                result = (loopResult != null) ? loopResult : WorkUnit.batch(spanQueue.drainBatch());
            }
        } finally {
            lock.unlock();
        }

        FlushTracker.completeAfterUnlock(completions);
        return result;
    }

    void settle(List<SequencedSpan> batch, boolean success) {
        List<FlushTracker.FlushCompletion> completions;
        lock.lock();
        try {
            spanQueue.resolve(batch);
            if (!success) {
                flushTracker.markFailureAtOrBefore(batch);
            }

            completions = collectTerminalFlushesLocked();
            workAvailable.signal();
        } finally {
            lock.unlock();
        }

        FlushTracker.completeAfterUnlock(completions);
    }

    InFlightExport registerTimedOut(List<SequencedSpan> batch, CompletableResultCode pendingResult) {
        InFlightExport created = new InFlightExport(batch, pendingResult);
        List<FlushTracker.FlushCompletion> completions;

        lock.lock();
        try {
            inFlightExport = created;
            flushTracker.markFailureAtOrBefore(batch);
            completions = collectTerminalFlushesLocked();
        } finally {
            lock.unlock();
        }

        FlushTracker.completeAfterUnlock(completions);
        return created;
    }

    void completeTimedOut(InFlightExport pending) {
        settle(pending.batch(), pending.resultCode().isSuccess());
        lock.lock();
        try {
            if (inFlightExport == pending) {
                inFlightExport = null;
                workAvailable.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    CompletableResultCode requestFlush() {
        List<FlushTracker.FlushCompletion> completions;
        CompletableResultCode result;
        lock.lock();
        try {
            if (lifecycleState != LifecycleState.ACTIVE) {
                return CompletableResultCode.ofFailure();
            }

            result = flushTracker.requestOrExtend(spanQueue.lastAdmittedSequence());
            flushTracker.markTimedOutFailureIfOverlapping(inFlightFirstSequenceOrNullLocked());
            completions = collectTerminalFlushesLocked();
            workAvailable.signal();
        } finally {
            lock.unlock();
        }

        FlushTracker.completeAfterUnlock(completions);
        return result;
    }

    void beginShutdown() {
        lock.lock();
        try {
            if (lifecycleState == LifecycleState.ACTIVE) {
                lifecycleState = LifecycleState.SHUTTING_DOWN;
                workAvailable.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    boolean markTerminated() {
        lock.lock();
        try {
            if (lifecycleState != LifecycleState.FAILED) {
                lifecycleState = LifecycleState.TERMINATED;
            }

            return lifecycleState == LifecycleState.FAILED;
        } finally {
            lock.unlock();
        }
    }

    void forceDiscardQueuedAndFailFlushes() {
        List<FlushTracker.FlushCompletion> completions;
        lock.lock();
        try {
            List<SequencedSpan> discarded = spanQueue.drainAll();
            metrics.addDiscardedQueued(discarded.size());
            flushTracker.failAllPending();
            completions = collectTerminalFlushesLocked();
            workAvailable.signal();
        } finally {
            lock.unlock();
        }

        FlushTracker.completeAfterUnlock(completions);
    }

    void failTerminal() {
        List<FlushTracker.FlushCompletion> completions;
        lock.lock();
        try {
            completions = failTerminalLocked();
        } finally {
            lock.unlock();
        }

        FlushTracker.completeAfterUnlock(completions);
    }

    LifecycleState lifecycleState() {
        return lifecycleState;
    }

    int pendingFlushGenerationCount() {
        lock.lock();
        try {
            return flushTracker.pendingGenerationCount();
        } finally {
            lock.unlock();
        }
    }

    BufferSnapshot snapshot() {
        lock.lock();
        try {
            return new BufferSnapshot(spanQueue.size(), lifecycleState);
        } finally {
            lock.unlock();
        }
    }

    private List<FlushTracker.FlushCompletion> failTerminalLocked() {
        if (lifecycleState == LifecycleState.FAILED) {
            return List.of();
        }

        lifecycleState = LifecycleState.FAILED;
        List<SequencedSpan> discarded = spanQueue.drainAll();
        metrics.addDiscardedQueued(discarded.size());
        flushTracker.failAllPending();
        List<FlushTracker.FlushCompletion> completions = collectTerminalFlushesLocked();
        workAvailable.signal();

        return completions;
    }

    private boolean isWorkerDoneLocked() {
        return lifecycleState != LifecycleState.ACTIVE && spanQueue.isEmpty() && inFlightExport == null;
    }

    private boolean shouldExportNowLocked(long scheduleDelayNanos, long elapsedSinceExportNanos) {
        if (inFlightExport != null) {
            return false;
        }

        if (lifecycleState != LifecycleState.ACTIVE) {
            return true;
        }

        if (flushTracker.hasActive()) {
            return true;
        }

        if (spanQueue.hasBatchReady()) {
            return true;
        }

        return !spanQueue.isEmpty() && elapsedSinceExportNanos >= scheduleDelayNanos;
    }

    private List<FlushTracker.FlushCompletion> collectTerminalFlushesLocked() {
        return flushTracker.collectTerminal(spanQueue::isResolvedUpTo, inFlightFirstSequenceOrNullLocked());
    }

    private Long inFlightFirstSequenceOrNullLocked() {
        return (inFlightExport == null) ? null : inFlightExport.firstSequence();
    }

    record BufferSnapshot(int queueSize, LifecycleState lifecycleState) {
    }

    static final class WorkUnit {
        private final List<SequencedSpan> batch;

        private WorkUnit(List<SequencedSpan> batch) {
            this.batch = batch;
        }

        static WorkUnit batch(List<SequencedSpan> batch) {
            return new WorkUnit(batch);
        }

        static WorkUnit terminate() {
            return new WorkUnit(null);
        }

        boolean isTerminate() {
            return batch == null;
        }

        List<SequencedSpan> batch() {
            return batch;
        }
    }

    record InFlightExport(List<SequencedSpan> batch, CompletableResultCode resultCode) {
        long firstSequence() {
            return batch.getFirst().sequence();
        }
    }
}
