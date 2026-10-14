package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.event.Level;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TracingSettingsRefreshCoalescer {

    private static final int MAX_ATTEMPTS = 2;

    private final ConfigServerSettingsManifestBuilder manifestBuilder;
    private final TracingSettingsWriter settingsWriter;
    private final Executor worker;

    private final AtomicLong requestedGeneration = new AtomicLong();
    private final AtomicBoolean workerScheduled = new AtomicBoolean();

    public SettingsDelivery deliverInitial() {
        return deliver(DeliveryPhase.INITIAL);
    }

    public void onRefreshEvent() {
        requestedGeneration.incrementAndGet();
        scheduleWorkerIfIdle();
    }

    private void scheduleWorkerIfIdle() {
        if (!workerScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::runWorker);
        } catch (RejectedExecutionException workerShutDown) {
            workerScheduled.set(false);
            log.debug("Config Server settings refresh skipped: worker is shut down");
        }
    }

    private void runWorker() {
        long servedGeneration = requestedGeneration.get();
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.error("Config Server settings refresh failed; LKG retained. errorType={}",
                    failure.getClass().getName(), failure);
        } finally {
            workerScheduled.set(false);
            if (requestedGeneration.get() > servedGeneration) {
                scheduleWorkerIfIdle();
            }
        }
    }

    private void refresh() {
        try {
            deliver(DeliveryPhase.REFRESH);
        } catch (TracingSettingsManifestException | IllegalArgumentException invalidManifest) {
            log.error("Config Server manifest rejected locally; LKG retained: {}", invalidManifest.getMessage());
        }
    }

    private SettingsDelivery deliver(DeliveryPhase phase) {
        SettingsDelivery delivery;

        int attempt = 0;
        do {
            attempt++;
            CompleteTracingSettings manifest = manifestBuilder.build();
            SettingsRevision base = expectedBase(phase);
            delivery = settingsWriter.applySnapshot(base, manifest, SettingsWriter.CONFIG_SERVER);
            logDelivery(phase, attempt, base, delivery);
        } while (delivery.outcome() == TracingSettingsDeliveryResult.STALE && attempt < MAX_ATTEMPTS);

        return delivery;
    }

    private SettingsRevision expectedBase(DeliveryPhase phase) {
        AcceptedSettings accepted = settingsWriter.current()
                .orElseThrow(() -> {
                    String message = "[CONFIG_SERVER %s] accepted settings are unreadable; expected base is unknown"
                            .formatted(phase);

                    return new TracingConfigurationGuardException(message);
                });

        if (accepted.authority() == AcceptedSettingsAuthority.ACTUATOR) {
            log.warn("Accepted settings were written by ACTUATOR in CONFIG_SERVER channel; overwriting. base={}",
                    accepted.revision());
        }
        return accepted.revision();
    }

    private static void logDelivery(DeliveryPhase phase,
                                    int attempt,
                                    SettingsRevision base,
                                    SettingsDelivery delivery) {
        Level level = levelOf(delivery.outcome(), attempt);
        log.atLevel(level)
                .log(
                        "Config Server settings delivery: phase={}, attempt={}/{}, attemptId={}, outcome={}, messageCode={}, base={}",
                        phase, attempt, MAX_ATTEMPTS, delivery.attemptId(), delivery.outcome(), delivery.messageCode(), base
                );
    }

    private static Level levelOf(TracingSettingsDeliveryResult outcome, int attempt) {
        return switch (outcome) {
            case APPLIED, UNCHANGED -> Level.INFO;
            case STALE -> attempt < MAX_ATTEMPTS ? Level.INFO : Level.WARN;
            case REJECTED -> Level.WARN;
            case FAILED -> Level.ERROR;
        };
    }

    private enum DeliveryPhase {
        INITIAL,
        REFRESH
    }
}
