package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.CompleteTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySource;

/**
 * Coalescing single-worker for Config Server refresh events.
 * <p>
 * Generation-based reschedule: worker captures generation at start;
 * reschedules only if generation advanced during apply (new events arrived).
 * <p>
 * Sequence contract:
 * All authoritative (runtimeInstanceId, acceptedSequence) pairs come from Agent via
 * readAcceptedComplete, and are always passed together as one {@link ExpectedTracingPolicyBase} —
 * never as a bare sequence with an identity taken from elsewhere. Application never computes
 * or increments the sequence unilaterally.
 * lastKnownBase is refreshed from Agent on APPLIED, UNCHANGED, STALE. If no base has been
 * observed yet (first refresh before {@link #deliverInitial()} ever warmed it), the worker
 * performs one authoritative read before applying; if that read also fails, the refresh is
 * abandoned and LKG is retained — there is no unconditional/unwarmed apply.
 */
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
            // Ловим строго вокруг единственного build(): completeness/parse → ManifestException,
            // value-invariants → IllegalArgumentException. Не расширяем до RuntimeException,
            // чтобы programming defects не маскировались как invalid refresh.
            snapshot = manifestBuilder.build();
        } catch (TracingPolicyManifestException e) {
            String s = isStaleRetry ? " (STALE retry)" : "";
            log.error("Config Server manifest build failed{}; LKG retained: {}", s, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            String s = isStaleRetry ? " (STALE retry)" : "";
            log.error("Config Server policy value invalid{}; LKG retained: {}", s, e.getMessage());
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
            // Startup fail-fast: невалидное value не маскируем — блокируем старт.
            log.error("Initial Config Server policy value invalid: {}", e.getMessage());
            throw e;
        }
    }

    /** Читает актуальную (runtimeInstanceId, acceptedSequence) пару у Agent и кеширует её. */
    private void refreshLastKnownBase() {
        snapshotReader.currentComplete()
                .map(AcceptedSnapshotWithSequence::expectedBase)
                .ifPresent(base -> lastKnownBase = base);
    }

    /** Возвращает кешированную base, либо выполняет один authoritative read, если её ещё нет. */
    private ExpectedTracingPolicyBase currentOrWarmedBase() {
        ExpectedTracingPolicyBase base = lastKnownBase;
        if (base != null) {
            return base;
        }
        refreshLastKnownBase();
        return lastKnownBase;
    }
}
