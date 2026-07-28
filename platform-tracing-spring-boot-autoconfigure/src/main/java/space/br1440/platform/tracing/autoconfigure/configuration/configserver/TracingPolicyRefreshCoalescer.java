package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
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
 * <strong>Generation-based reschedule (fixes infinite loop):</strong>
 * The worker captures the generation counter at start. After apply, it checks
 * if the generation has advanced. Only if new events arrived does it reschedule.
 * If no new events arrived, the worker exits cleanly without rescheduling.
 * <p>
 * Concurrency contract:
 * - {@code generation} is the dirty counter; incremented on each event.
 * - {@code workerScheduled} guards exactly-once scheduling.
 * - Worker compares captured-generation at start vs at end to detect new events.
 */
public final class TracingPolicyRefreshCoalescer {

    private static final Logger log = LoggerFactory.getLogger(TracingPolicyRefreshCoalescer.class);

    private final ConfigServerPolicyManifestBuilder manifestBuilder;
    private final TracingPolicySnapshotClient snapshotClient;
    private final AcceptedTracingPolicySnapshotReader snapshotReader;
    private final Executor worker;

    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicBoolean workerScheduled = new AtomicBoolean(false);

    private volatile long lastKnownSequence = -1L;

    public TracingPolicyRefreshCoalescer(
            ConfigServerPolicyManifestBuilder manifestBuilder,
            TracingPolicySnapshotClient snapshotClient,
            AcceptedTracingPolicySnapshotReader snapshotReader) {
        this.manifestBuilder = manifestBuilder;
        this.snapshotClient = snapshotClient;
        this.snapshotReader = snapshotReader;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tracing-policy-refresh-worker");
            t.setDaemon(true);
            return t;
        });
    }

    TracingPolicyRefreshCoalescer(
            ConfigServerPolicyManifestBuilder manifestBuilder,
            TracingPolicySnapshotClient snapshotClient,
            AcceptedTracingPolicySnapshotReader snapshotReader,
            Executor worker) {
        this.manifestBuilder = manifestBuilder;
        this.snapshotClient = snapshotClient;
        this.snapshotReader = snapshotReader;
        this.worker = worker;
    }

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
        // Capture generation at start of this worker cycle
        long capturedGeneration = generation.get();
        try {
            doApply(false);
        } finally {
            // Clear scheduled flag BEFORE checking generation
            workerScheduled.set(false);
            // Reschedule only if new events arrived during this worker cycle
            // (generation advanced beyond what we captured at start)
            if (generation.get() > capturedGeneration) {
                scheduleWorkerIfAbsent();
            }
            // If generation == capturedGeneration: no new events; worker exits cleanly.
        }
    }

    private void doApply(boolean isStaleRetry) {
        CompleteTracingPolicySnapshot snapshot;
        try {
            snapshot = manifestBuilder.build();
        } catch (TracingPolicyManifestException e) {
            log.error("Config Server manifest build failed{}; LKG retained: {}",
                    isStaleRetry ? " (STALE retry)" : "", e.getMessage());
            return;
        }

        long seq = lastKnownSequence;
        TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(seq, snapshot, TracingPolicySource.CONFIG_SERVER);
        log.info("Config Server policy refresh: outcome={}, seq={}, staleRetry={}", result, seq, isStaleRetry);

        switch (result) {
            case APPLIED -> {
                lastKnownSequence = seq + 1;
                // Refresh from Agent to stay in sync
                snapshotReader.currentComplete().ifPresent(c -> lastKnownSequence = c.acceptedSequence());
            }
            case UNCHANGED -> { /* no-op */ }
            case STALE -> {
                if (!isStaleRetry) {
                    // Refresh sequence from Agent and retry once
                    snapshotReader.currentComplete().ifPresent(c -> lastKnownSequence = c.acceptedSequence());
                    log.info("STALE on Config Server refresh; one bounded retry with refreshed sequence.");
                    doApply(true);
                } else {
                    log.warn("STALE on bounded retry; LKG retained.");
                }
            }
            case REJECTED -> log.warn("Policy snapshot REJECTED by Agent; LKG retained.");
            case FAILED   -> log.error("Policy apply FAILED; LKG retained.");
        }
    }

    public TracingPolicyDeliveryResult deliverInitial() {
        try {
            CompleteTracingPolicySnapshot snapshot = manifestBuilder.build();
            TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(
                    -1L, snapshot, TracingPolicySource.CONFIG_SERVER);
            if (result == TracingPolicyDeliveryResult.APPLIED ||
                    result == TracingPolicyDeliveryResult.UNCHANGED) {
                snapshotReader.currentComplete().ifPresent(c -> lastKnownSequence = c.acceptedSequence());
            }
            return result;
        } catch (TracingPolicyManifestException e) {
            log.error("Initial Config Server manifest missing required leaf: {}", e.getMessage());
            throw e;
        }
    }
}
