package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TracingSettingsRefreshCoalescer {

    private final ConfigServerSettingsManifestBuilder manifestBuilder;
    private final TracingSettingsApplier snapshotClient;
    private final CompleteTracingSettingsReader snapshotReader;
    private final Executor worker;

    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicBoolean workerScheduled = new AtomicBoolean(false);
    private volatile ExpectedSettingsRevision lastKnownBase;

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
        CompleteTracingSettings snapshot;
        try {
            snapshot = manifestBuilder.build();
        } catch (TracingSettingsManifestException e) {
            String state = isStaleRetry ? " (STALE retry)" : "";
            log.error("Config Server manifest build failed{}; LKG retained: {}", state, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            String state = isStaleRetry ? " (STALE retry)" : "";
            log.error("Config Server policy value invalid{}; LKG retained: {}", state, e.getMessage());
            return;
        }

        ExpectedSettingsRevision base = currentOrWarmedBase();
        if (base == null) {
            log.error("Config Server refresh: no authoritative expected base available from Agent; LKG retained.");
            return;
        }

        TracingSettingsDeliveryResult result = snapshotClient.applySnapshot(base, snapshot, TracingSettingsDeliverySource.CONFIG_SERVER);
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

            case REJECTED -> log.warn("Settings snapshot REJECTED by Agent; LKG retained.");

            case FAILED -> log.error("Settings apply FAILED; LKG retained.");
        }
    }

    public TracingSettingsDeliveryResult deliverInitial() {
        TracingSettingsDeliveryResult result = attemptInitialDelivery(false);
        if (result != TracingSettingsDeliveryResult.STALE) {
            return result;
        }

        return attemptInitialDelivery(true);
    }

    private TracingSettingsDeliveryResult attemptInitialDelivery(boolean isStaleRetry) {
        try {
            CompleteTracingSettings snapshot = manifestBuilder.build();
            refreshLastKnownBase();

            ExpectedSettingsRevision base = lastKnownBase;
            if (base == null) {
                throw new TracingConfigurationGuardException(
                        "Initial Config Server delivery: no authoritative expected base "
                                + "available from Agent (AcceptedPolicySnapshot is absent)");
            }

            TracingSettingsDeliveryResult result = snapshotClient.applySnapshot(base, snapshot, TracingSettingsDeliverySource.CONFIG_SERVER);
            if (result == TracingSettingsDeliveryResult.APPLIED || result == TracingSettingsDeliveryResult.UNCHANGED) {
                refreshLastKnownBase();
            }
            return result;
        } catch (TracingSettingsManifestException e) {
            log.error("Initial Config Server manifest missing required leaf (staleRetry={}): {}", isStaleRetry, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            log.error("Initial Config Server policy value invalid (staleRetry={}): {}", isStaleRetry, e.getMessage());
            throw e;
        }
    }

    private void refreshLastKnownBase() {
        snapshotReader.currentComplete()
                .map(AcceptedSettingsWithSequence::expectedBase)
                .ifPresent(base -> lastKnownBase = base);
    }

    private ExpectedSettingsRevision currentOrWarmedBase() {
        ExpectedSettingsRevision base = lastKnownBase;
        if (base != null) {
            return base;
        }

        refreshLastKnownBase();
        return lastKnownBase;
    }
}
