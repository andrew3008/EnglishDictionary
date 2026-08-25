package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.util.concurrent.atomic.AtomicLong;

final class DropOldestExportMetrics {

    private final AtomicLong droppedOverflowTotal = new AtomicLong();
    private final AtomicLong droppedAfterShutdownTotal = new AtomicLong();
    private final AtomicLong droppedAfterFailureTotal = new AtomicLong();
    private final AtomicLong discardedQueuedTotal = new AtomicLong();
    private final AtomicLong failuresTotal = new AtomicLong();
    private final AtomicLong shutdownFailuresTotal = new AtomicLong();
    private final AtomicLong timeoutsTotal = new AtomicLong();

    void incrementDroppedOverflow() {
        droppedOverflowTotal.incrementAndGet();
    }

    void incrementDroppedAfterShutdown() {
        droppedAfterShutdownTotal.incrementAndGet();
    }

    void incrementDroppedAfterFailure() {
        droppedAfterFailureTotal.incrementAndGet();
    }

    void addDiscardedQueued(long delta) {
        if (delta > 0) {
            discardedQueuedTotal.addAndGet(delta);
        }
    }

    void incrementFailures() {
        failuresTotal.incrementAndGet();
    }

    void incrementShutdownFailures() {
        shutdownFailuresTotal.incrementAndGet();
    }

    void incrementTimeouts() {
        timeoutsTotal.incrementAndGet();
    }

    long droppedOverflowTotal() {
        return droppedOverflowTotal.get();
    }

    long droppedAfterShutdownTotal() {
        return droppedAfterShutdownTotal.get();
    }

    long droppedAfterFailureTotal() {
        return droppedAfterFailureTotal.get();
    }

    long discardedQueuedTotal() {
        return discardedQueuedTotal.get();
    }

    long failuresTotal() {
        return failuresTotal.get();
    }

    long shutdownFailuresTotal() {
        return shutdownFailuresTotal.get();
    }

    long timeoutsTotal() {
        return timeoutsTotal.get();
    }
}
