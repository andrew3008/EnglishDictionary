package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySource;

/**
 * Coalescing single-worker for Config Server refresh events.
 * <p>
 * Generation-based reschedule: worker captures generation at start;
 * reschedules only if generation advanced during apply (new events arrived).
 * <p>
 * Sequence contract:
 * All authoritative sequence values come from Agent via readAcceptedComplete.
 * Application never computes or increments sequence unilaterally.
 * lastKnownSequence is refreshed from Agent on APPLIED, UNCHANGED, STALE.
 * There is no "unconditional apply" sentinel: the Agent (AcceptedPolicyCoordinator.staleCode)
 * always compares the supplied expectedSequence/runtimeInstanceId against its own witnessed
 * state, so an unwarmed sequence is simply treated as STALE (or worse — see deliverInitial).
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TracingPolicyRefreshCoalescer {

    private final ConfigServerPolicyManifestBuilder manifestBuilder;
    private final TracingPolicySnapshotClient snapshotClient;
    private final AcceptedTracingPolicySnapshotReader snapshotReader;
    private final Executor worker;

    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicBoolean workerScheduled = new AtomicBoolean(false);
    private volatile long lastKnownSequence = -1L;

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
        long capturedGeneration = generation.get();

        try {
            doApply(false);
        } finally {
            workerScheduled.set(false);
            if (generation.get() > capturedGeneration) {
                scheduleWorkerIfAbsent();
            }
        }
    }

    private void doApply(boolean isStaleRetry) {
        CompleteTracingPolicySnapshot snapshot;
        try {
            snapshot = manifestBuilder.build();
        } catch (TracingPolicyManifestException e) {
            String s = isStaleRetry ? " (STALE retry)" : "";
            log.error("Config Server manifest build failed{}; LKG retained: {}", s, e.getMessage());
            return;
        }

        long seq = lastKnownSequence;
        TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(seq, snapshot, TracingPolicySource.CONFIG_SERVER);
        log.info("Config Server policy refresh: outcome={}, seq={}, staleRetry={}", result, seq, isStaleRetry);

        switch (result) {
            case APPLIED -> snapshotReader.currentComplete()
                            .ifPresent(c -> lastKnownSequence = c.acceptedSequence());

            case UNCHANGED -> {}

            case STALE -> {
                if (!isStaleRetry) {
                    snapshotReader.currentComplete()
                            .ifPresent(c -> lastKnownSequence = c.acceptedSequence());
                    log.info("STALE; refreshed sequence from Agent; one bounded retry.");
                    doApply(true);
                } else {
                    log.warn("STALE on bounded retry; LKG retained.");
                }
            }

            case REJECTED -> log.warn("Policy snapshot REJECTED by Agent; LKG retained.");

            case FAILED -> log.error("Policy apply FAILED; LKG retained.");
        }
    }

    public TracingPolicyDeliveryResult deliverInitial() {
        try {
            CompleteTracingPolicySnapshot snapshot = manifestBuilder.build();
            snapshotReader.currentComplete()
                    .ifPresent(c -> lastKnownSequence = c.acceptedSequence());

            long seq = lastKnownSequence;
            TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(seq, snapshot, TracingPolicySource.CONFIG_SERVER);
            if (result == TracingPolicyDeliveryResult.APPLIED || result == TracingPolicyDeliveryResult.UNCHANGED) {
                snapshotReader.currentComplete()
                        .ifPresent(c -> lastKnownSequence = c.acceptedSequence());
            }
            return result;
        } catch (TracingPolicyManifestException e) {
            log.error("Initial Config Server manifest missing required leaf: {}", e.getMessage());
            throw e;
        }
    }
}
