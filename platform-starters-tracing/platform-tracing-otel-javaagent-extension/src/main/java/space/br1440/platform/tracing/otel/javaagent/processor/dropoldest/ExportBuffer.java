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

                if (spanQueue.hasBatchReady() || flushTracker.hasActive()) {
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

    void completeExport(SpanQueue.Batch batch, boolean success) {
        Objects.requireNonNull(batch, "batch");

        LockedEffects effects = new LockedEffects();
        lock.lock();
        try {
            if (batch != inFlightBatch) {
                throw new IllegalStateException("Export batch does not match the current in-flight batch");
            }

            if (!success) {
                flushTracker.failGenerationsCovering(batch.firstSequence());
            }

            inFlightBatch = null;
            if (isShutdownDeadlineExceededLocked(nanoTime.getAsLong())) {
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

    CompletableResultCode requestFlush() {
        LockedEffects effects = new LockedEffects();
        lock.lock();
        try {
            if (lifecycleState != LifecycleState.ACTIVE) {
                return CompletableResultCode.ofFailure();
            }

            CompletableResultCode result = flushTracker.requestOrExtend(spanQueue.lastAdmittedSequence());
            effects.addCompletions(collectTerminalFlushesLocked());
            workAvailable.signal();
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

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private WorkerAction awaitNextActionLocked(long scheduleDelayNanos,
                                               long lastExportNanos,
                                               LockedEffects effects) {
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
                inFlightBatch = spanQueue.drainBatch();
                return new WorkerAction.ExportBatch(inFlightBatch);
            }

            try {
                workAvailable.awaitNanos(workerWaitNanosLocked(scheduleDelayNanos, elapsedSinceExportNanos, nowNanos));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failTerminalLocked(effects);
                return new WorkerAction.StopWorker(WorkerStopReason.WORKER_FAILED);
            }
        }
    }

    private boolean isExportReadyLocked(long scheduleDelayNanos, long elapsedSinceExportNanos) {
        if (inFlightBatch != null || spanQueue.isEmpty()) {
            return false;
        }

        if (lifecycleState != LifecycleState.ACTIVE || flushTracker.hasActive()) {
            return true;
        }

        return spanQueue.hasBatchReady() || elapsedSinceExportNanos >= scheduleDelayNanos;
    }

    private long workerWaitNanosLocked(long scheduleDelayNanos, long elapsedSinceExportNanos, long nowNanos) {
        long waitNanos = spanQueue.isEmpty()
                ? scheduleDelayNanos
                : Math.max(MIN_WORKER_AWAIT_NANOS, scheduleDelayNanos - elapsedSinceExportNanos);

        if (lifecycleState == LifecycleState.SHUTTING_DOWN) {
            waitNanos = Math.min(waitNanos, requireShutdownDrainLocked().remainingNanos(nowNanos));
        }

        return waitNanos;
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
        long queueHead = spanQueue.headSequence();
        if (inFlightBatch == null) {
            return queueHead;
        }

        if (queueHead == SpanQueue.NONE) {
            return inFlightBatch.firstSequence();
        }

        return Math.min(inFlightBatch.firstSequence(), queueHead);
    }

    private void recordRejectedAdmission(LifecycleState rejectedState) {
        if (rejectedState == LifecycleState.FAILED) {
            metrics.incrementDroppedAfterFailure();
        } else {
            metrics.incrementDroppedAfterShutdown();
        }
    }
}
