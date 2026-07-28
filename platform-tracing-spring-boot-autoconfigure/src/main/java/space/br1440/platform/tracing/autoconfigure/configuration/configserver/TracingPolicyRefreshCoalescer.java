package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySource;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coalescing single-worker for Config Server refresh events.
 * <p>
 * On each {@link #onRefreshEvent()}, marks dirty and schedules one worker task.
 * Worker rebuilds the complete manifest and calls applySnapshot.
 * Repeated events before worker completion collapse to one rebuild.
 * No lost final refresh: dirty flag checked after each apply.
 * One bounded STALE retry only. No retry on REJECTED or FAILED.
 * EnvironmentChangeEvent keys are triggers only; no patch apply.
 */
public final class TracingPolicyRefreshCoalescer {

    private static final Logger log = LoggerFactory.getLogger(TracingPolicyRefreshCoalescer.class);

    private final ConfigServerPolicyManifestBuilder manifestBuilder;
    private final TracingPolicySnapshotClient snapshotClient;
    private final Executor worker;

    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicBoolean workerScheduled = new AtomicBoolean(false);

    // acceptedSequence from last successful apply; -1 = unknown
    private volatile long lastKnownSequence = -1L;

    public TracingPolicyRefreshCoalescer(
            ConfigServerPolicyManifestBuilder manifestBuilder,
            TracingPolicySnapshotClient snapshotClient) {
        this.manifestBuilder = manifestBuilder;
        this.snapshotClient = snapshotClient;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tracing-policy-refresh-worker");
            t.setDaemon(true);
            return t;
        });
    }

    // Package-private for testing: inject custom executor
    TracingPolicyRefreshCoalescer(
            ConfigServerPolicyManifestBuilder manifestBuilder,
            TracingPolicySnapshotClient snapshotClient,
            Executor worker) {
        this.manifestBuilder = manifestBuilder;
        this.snapshotClient = snapshotClient;
        this.worker = worker;
    }

    /**
     * Called on each EnvironmentChangeEvent. Keys are NOT used as patch targets.
     * Marks generation dirty and schedules one coalesced worker task.
     */
    public void onRefreshEvent() {
        generation.incrementAndGet();
        scheduleWorkerIfAbsent();
    }

    private void scheduleWorkerIfAbsent() {
        if (workerScheduled.compareAndSet(false, true)) {
            worker.execute(this::runWorker);
        }
    }

    private void runWorker() {
        try {
            long capturedGeneration = generation.get();
            doApply(false);
            // Check if new events arrived during apply
            if (generation.get() != capturedGeneration) {
                // Lost-final-refresh guard: do one more apply
                doApply(false);
            }
        } finally {
            workerScheduled.set(false);
            // If dirty again after we cleared the flag, reschedule
            scheduleWorkerIfAbsent();
        }
    }

    private void doApply(boolean isStaleRetry) {
        CompleteTracingPolicySnapshot snapshot;
        try {
            snapshot = manifestBuilder.build();
        } catch (TracingPolicyManifestException e) {
            log.error("Config Server manifest build failed{}; REJECTED, LKG retained: {}",
                    isStaleRetry ? " (STALE retry)" : "", e.getMessage());
            return; // REJECTED, no retry
        }

        long seq = lastKnownSequence;
        TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(seq, snapshot, TracingPolicySource.CONFIG_SERVER);
        log.info("Config Server policy refresh: outcome={}, seq={}, retry={}", result, seq, isStaleRetry);

        switch (result) {
            case APPLIED -> lastKnownSequence = seq + 1;
            case UNCHANGED -> { /* no-op */ }
            case STALE -> {
                if (!isStaleRetry) {
                    // One bounded full rebuild/retry after STALE
                    log.info("STALE on Config Server refresh; performing one bounded full rebuild.");
                    // Refresh known sequence from Agent
                    snapshotClient.current().ifPresent(v -> lastKnownSequence = v.acceptedSequence());
                    doApply(true);
                } else {
                    log.warn("STALE on retry; accepted state unchanged, LKG retained.");
                }
            }
            case REJECTED -> log.warn("Policy snapshot REJECTED by Agent; LKG retained.");
            case FAILED -> log.error("Policy apply FAILED; LKG retained.");
        }
    }

    /** Initial delivery at application startup. Blocks readiness on failure. */
    public TracingPolicyDeliveryResult deliverInitial() {
        try {
            CompleteTracingPolicySnapshot snapshot = manifestBuilder.build();
            TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(
                    -1L, snapshot, TracingPolicySource.CONFIG_SERVER);
            if (result == TracingPolicyDeliveryResult.APPLIED ||
                    result == TracingPolicyDeliveryResult.UNCHANGED) {
                snapshotClient.current().ifPresent(v -> lastKnownSequence = v.acceptedSequence());
            }
            return result;
        } catch (TracingPolicyManifestException e) {
            log.error("Initial Config Server manifest missing required leaf: {}", e.getMessage());
            throw e; // blocks application readiness
        }
    }
}
