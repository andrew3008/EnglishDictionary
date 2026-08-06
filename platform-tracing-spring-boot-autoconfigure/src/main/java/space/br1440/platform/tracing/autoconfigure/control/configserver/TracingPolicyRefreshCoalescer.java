package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySource;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.CompleteTracingPolicyReader;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TracingPolicyRefreshCoalescer {

    private final ConfigServerPolicyManifestBuilder manifestBuilder;
    private final TracingPolicySnapshotApplier snapshotClient;
    private final CompleteTracingPolicyReader snapshotReader;
    private final Executor worker;

    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicBoolean workerScheduled = new AtomicBoolean(false);
    private volatile ExpectedTracingPolicyBase lastKnownBase;

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
            String state = isStaleRetry ? " (STALE retry)" : "";
            log.error("Config Server manifest build failed{}; LKG retained: {}", state, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            String state = isStaleRetry ? " (STALE retry)" : "";
            log.error("Config Server policy value invalid{}; LKG retained: {}", state, e.getMessage());
            return;
        }

        ExpectedTracingPolicyBase base = currentOrWarmedBase();
        if (base == null) {
            log.error("Config Server refresh: no authoritative expected base available from Agent; LKG retained.");
            return;
        }

        TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(base, snapshot, TracingPolicySource.CONFIG_SERVER);
        log.info("Config Server policy refresh: outcome={}, base={}, staleRetry={}", result, base, isStaleRetry);

        switch (result) {
            case APPLIED -> refreshLastKnownBase();

            case UNCHANGED -> {}

            case STALE -> {
                if (!isStaleRetry) {
                    refreshLastKnownBase();
                    log.info("STALE; refreshed expected base from Agent; one bounded retry.");
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
            refreshLastKnownBase();

            ExpectedTracingPolicyBase base = lastKnownBase;
            if (base == null) {
                throw new IllegalStateException(
                        "Initial Config Server delivery: no authoritative expected base "
                                + "available from Agent (readAcceptedComplete returned empty)");
            }

            TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(base, snapshot, TracingPolicySource.CONFIG_SERVER);
            if (result == TracingPolicyDeliveryResult.APPLIED || result == TracingPolicyDeliveryResult.UNCHANGED) {
                refreshLastKnownBase();
            }
            return result;
        } catch (TracingPolicyManifestException e) {
            log.error("Initial Config Server manifest missing required leaf: {}", e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            log.error("Initial Config Server policy value invalid: {}", e.getMessage());
            throw e;
        }
    }

    private void refreshLastKnownBase() {
        snapshotReader.currentComplete()
                .map(AcceptedSnapshotWithSequence::expectedBase)
                .ifPresent(base -> lastKnownBase = base);
    }

    private ExpectedTracingPolicyBase currentOrWarmedBase() {
        ExpectedTracingPolicyBase base = lastKnownBase;
        if (base != null) {
            return base;
        }

        refreshLastKnownBase();
        return lastKnownBase;
    }
}
