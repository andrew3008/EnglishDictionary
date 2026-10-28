package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;

final class ExportBuffer {

    private static final long MIN_WORKER_AWAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final DropOldestExportMetrics metrics;
    private final LongSupplier nanoTime;
    private final SpanQueue spanQueue;
    private final FlushTracker flushTracker = new FlushTracker();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();

    private volatile LifecycleState lifecycleState = LifecycleState.ACTIVE;

    private SpanQueue.Batch inFlightBatch;

    private boolean delegatePending;
    private long delegatePendingSinceNanos;

    private ShutdownDrain shutdownDrain;

    ExportBuffer(int maxQueueSize, int maxExportBatchSize, DropOldestExportMetrics metrics) {
        this(maxQueueSize, maxExportBatchSize, metrics, System::nanoTime);
    }

    ExportBuffer(int maxQueueSize,
                 int maxExportBatchSize,
                 DropOldestExportMetrics metrics,
                 LongSupplier nanoTime) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.spanQueue = new SpanQueue(maxQueueSize, maxExportBatchSize);
    }

    boolean isAdmissionOpen() {
        return (lifecycleState == LifecycleState.ACTIVE);
    }

    void rejectAdmission() {
        recordRejectedAdmission(lifecycleState);
    }

    void admit(SpanData snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        LifecycleState rejectedState = null;
        boolean overflowed = false;
        List<FlushTracker.FlushCompletion> completions = List.of();
        lock.lock();
        try {
            if (lifecycleState != LifecycleState.ACTIVE) {
                rejectedState = lifecycleState;
            } else {
                long droppedSequence = spanQueue.admit(snapshot);
                if (droppedSequence != SpanQueue.NONE) {
                    flushTracker.failGenerationsCovering(droppedSequence);
                    completions = collectTerminalFlushesLocked();
                    overflowed = true;
                }

                if (!delegatePending && (spanQueue.hasBatchReady() || flushTracker.hasActive())) {
                    workAvailable.signal();
                }
            }
        } finally {
            lock.unlock();
        }

        if (rejectedState != null) {
            recordRejectedAdmission(rejectedState);
            return;
        }

        try {
            if (overflowed) {
                metrics.incrementDroppedOverflow();
            }
        } finally {
            FlushTracker.completeAfterUnlock(completions);
        }
    }

    WorkerAction awaitNextAction(long scheduleDelayNanos, long lastExportNanos) {
        LockedEffects effects = new LockedEffects();
        lock.lock();
        try {
            return awaitNextActionLocked(scheduleDelayNanos, lastExportNanos, effects);
        } finally {
            lock.unlock();
            effects.publish(metrics);
        }
    }

    void completeExport(SpanQueue.Batch batch, boolean success, boolean delegateStillPending) {
        Objects.requireNonNull(batch, "batch");
        if (delegateStillPending && success) {
            throw new IllegalArgumentException("A pending delegate export cannot be successful");
        }

        LockedEffects effects = new LockedEffects();
        lock.lock();
        try {
            if (batch != inFlightBatch) {
                throw new IllegalStateException("Export batch does not match the current in-flight batch");
            }

            if (!success) {
                flushTracker.failGenerationsCovering(batch.firstSequence());
            }

            long nowNanos = nanoTime.getAsLong();
            inFlightBatch = null;
            delegatePending = delegateStillPending;
            delegatePendingSinceNanos = nowNanos;
            if (isShutdownDeadlineExceededLocked(nowNanos)) {
                expireShutdownDrainLocked(effects);
            } else {
                effects.addCompletions(collectTerminalFlushesLocked());
            }

            workAvailable.signal();
        } finally {
            lock.unlock();
            effects.publish(metrics);
        }
    }

    long releaseDelegate() {
        lock.lock();
        try {
            delegatePending = false;
            workAvailable.signal();
            return nanoTime.getAsLong() - delegatePendingSinceNanos;
        } finally {
            lock.unlock();
        }
    }

    CompletableResultCode requestFlush() {
        LockedEffects effects = new LockedEffects();
        lock.lock();
        try {
            if (lifecycleState != LifecycleState.ACTIVE) {
                return CompletableResultCode.ofFailure();
            }

            CompletableResultCode result = flushTracker.requestOrExtend(spanQueue.lastAdmittedSequence());
            effects.addCompletions(collectTerminalFlushesLocked());
            if (!delegatePending) {
                workAvailable.signal();
            }
            return result;
        } finally {
            lock.unlock();
            effects.publish(metrics);
        }
    }

    void beginShutdown(long shutdownTimeoutNanos) {
        LockedEffects effects = new LockedEffects();
        lock.lock();
        try {
            if (lifecycleState != LifecycleState.ACTIVE) {
                return;
            }

            lifecycleState = LifecycleState.SHUTTING_DOWN;

            CompletableResultCode shutdownFlush = flushTracker.requestOrExtend(spanQueue.lastAdmittedSequence());
            shutdownDrain = new ShutdownDrain(nanoTime.getAsLong(), shutdownTimeoutNanos, shutdownFlush);
            effects.addCompletions(collectTerminalFlushesLocked());
            workAvailable.signal();
        } finally {
            lock.unlock();
            effects.publish(metrics);
        }
    }

    boolean markTerminated() {
        LockedEffects effects = new LockedEffects();
        lock.lock();

        try {
            if (lifecycleState == LifecycleState.SHUTTING_DOWN) {
                lifecycleState = LifecycleState.TERMINATED;
                releaseAllWorkLocked(effects);
            } else if (lifecycleState == LifecycleState.ACTIVE) {
                failTerminalLocked(effects);
            }

            return lifecycleState == LifecycleState.FAILED;
        } finally {
            lock.unlock();
            effects.publish(metrics);
        }
    }

    void failTerminal() {
        LockedEffects effects = new LockedEffects();
        lock.lock();

        try {
            failTerminalLocked(effects);
        } finally {
            lock.unlock();
            effects.publish(metrics);
        }
    }

    LifecycleState lifecycleState() {
        return lifecycleState;
    }

    long currentTimeNanos() {
        return nanoTime.getAsLong();
    }

    BufferSnapshot snapshot() {
        lock.lock();
        try {
            return new BufferSnapshot(spanQueue.size(), lifecycleState);
        } finally {
            lock.unlock();
        }
    }

    record BufferSnapshot(int queueSize, LifecycleState lifecycleState) {
    }

    private WorkerAction awaitNextActionLocked(long scheduleDelayNanos,
                                               long lastExportNanos,
                                               LockedEffects effects) {
        boolean waitElapsed = false;

        while (true) {
            effects.addCompletions(collectTerminalFlushesLocked());
            if (isWorkerDoneLocked()) {
                return new WorkerAction.StopWorker(workerStopReasonLocked());
            }

            long nowNanos = nanoTime.getAsLong();
            if (isShutdownDeadlineExceededLocked(nowNanos)) {
                expireShutdownDrainLocked(effects);
                return new WorkerAction.StopWorker(WorkerStopReason.DRAIN_DEADLINE_EXCEEDED);
            }

            long elapsedSinceExportNanos = nowNanos - lastExportNanos;
            if (isExportReadyLocked(scheduleDelayNanos, elapsedSinceExportNanos)) {
                SpanQueue.Batch batch = spanQueue.drainBatch();
                if (batch == null) {
                    throw new IllegalStateException("Export-ready queue returned an empty batch");
                }
                inFlightBatch = batch;
                return new WorkerAction.ExportBatch(batch);
            }

            if (waitElapsed && delegatePending) {
                return new WorkerAction.DelegateHeld(nowNanos - delegatePendingSinceNanos);
            }

            try {
                long waitNanos = workerWaitNanosLocked(scheduleDelayNanos, elapsedSinceExportNanos, nowNanos);
                waitElapsed = workAvailable.awaitNanos(waitNanos) <= 0;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failTerminalLocked(effects);
                return new WorkerAction.StopWorker(WorkerStopReason.WORKER_FAILED);
            }
        }
    }

    private boolean isExportReadyLocked(long scheduleDelayNanos, long elapsedSinceExportNanos) {
        if (inFlightBatch != null || delegatePending || spanQueue.isEmpty()) {
            return false;
        }

        if (lifecycleState != LifecycleState.ACTIVE || flushTracker.hasActive()) {
            return true;
        }

        return spanQueue.hasBatchReady() || elapsedSinceExportNanos >= scheduleDelayNanos;
    }

    private long workerWaitNanosLocked(long scheduleDelayNanos, long elapsedSinceExportNanos, long nowNanos) {
        long waitNanos = idleWaitNanos(delegatePending, spanQueue.isEmpty(), scheduleDelayNanos, elapsedSinceExportNanos);
        if (lifecycleState == LifecycleState.SHUTTING_DOWN) {
            waitNanos = Math.min(waitNanos, requireShutdownDrainLocked().remainingNanos(nowNanos));
        }

        return waitNanos;
    }

    static long idleWaitNanos(boolean delegatePending,
                              boolean queueEmpty,
                              long scheduleDelayNanos,
                              long elapsedSinceExportNanos) {
        if (delegatePending || queueEmpty) {
            return scheduleDelayNanos;
        }

        return Math.max(MIN_WORKER_AWAIT_NANOS, scheduleDelayNanos - elapsedSinceExportNanos);
    }

    private boolean isWorkerDoneLocked() {
        return lifecycleState != LifecycleState.ACTIVE
                && spanQueue.isEmpty()
                && inFlightBatch == null;
    }

    private WorkerStopReason workerStopReasonLocked() {
        if (lifecycleState == LifecycleState.FAILED) {
            return WorkerStopReason.WORKER_FAILED;
        }

        return requireShutdownDrainLocked().stopReason();
    }

    private void failTerminalLocked(LockedEffects effects) {
        if (lifecycleState == LifecycleState.FAILED || lifecycleState == LifecycleState.TERMINATED) {
            return;
        }

        lifecycleState = LifecycleState.FAILED;
        releaseAllWorkLocked(effects);
    }

    private void expireShutdownDrainLocked(LockedEffects effects) {
        requireShutdownDrainLocked().markDeadlineExceeded();
        discardQueuedAndFailFlushesLocked(effects);
    }

    private void releaseAllWorkLocked(LockedEffects effects) {
        inFlightBatch = null;
        delegatePending = false;
        discardQueuedAndFailFlushesLocked(effects);
    }

    private void discardQueuedAndFailFlushesLocked(LockedEffects effects) {
        effects.addDiscardedQueued(spanQueue.drainAll());
        flushTracker.failAllPending();
        effects.addCompletions(collectTerminalFlushesLocked());
        workAvailable.signal();
    }

    private boolean isShutdownDeadlineExceededLocked(long nowNanos) {
        return (lifecycleState == LifecycleState.SHUTTING_DOWN)
                && requireShutdownDrainLocked().isDeadlineExceeded(nowNanos);
    }

    private ShutdownDrain requireShutdownDrainLocked() {
        if (shutdownDrain == null) {
            throw new IllegalStateException("Shutdown drain has not been initialized");
        }

        return shutdownDrain;
    }

    private List<FlushTracker.FlushCompletion> collectTerminalFlushesLocked() {
        if (!flushTracker.hasActive()) {
            return List.of();
        }

        List<FlushTracker.FlushCompletion> completions = flushTracker.collectTerminal(this::isResolvedUpToLocked);
        if (shutdownDrain != null) {
            shutdownDrain.captureFlushOutcome(completions);
        }
        return completions;
    }

    private boolean isResolvedUpToLocked(long watermark) {
        long lowestUnresolved = lowestUnresolvedSequenceLocked();
        return (lowestUnresolved == SpanQueue.NONE) || (lowestUnresolved > watermark);
    }

    private long lowestUnresolvedSequenceLocked() {
        SpanQueue.Batch inFlight = inFlightBatch;
        long queueHead = spanQueue.headSequence();
        if (inFlight == null) {
            return queueHead;
        }

        long inFlightSequence = inFlight.firstSequence();
        if (queueHead == SpanQueue.NONE) {
            return inFlightSequence;
        }

        return Math.min(inFlightSequence, queueHead);
    }

    private void recordRejectedAdmission(LifecycleState rejectedState) {
        if (rejectedState == LifecycleState.FAILED) {
            metrics.incrementDroppedAfterFailure();
        } else {
            metrics.incrementDroppedAfterShutdown();
        }
    }
}
