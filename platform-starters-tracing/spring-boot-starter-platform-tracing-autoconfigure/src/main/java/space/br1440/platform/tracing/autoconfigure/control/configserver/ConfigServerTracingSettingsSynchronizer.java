package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static java.util.Objects.requireNonNull;

import org.slf4j.event.Level;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ConfigServerTracingSettingsSynchronizer {

    private final ConfigServerSettingsManifestBuilder manifestBuilder;
    private final TracingSettingsWriter settingsWriter;

    ConfigServerTracingSettingsSynchronizer(ConfigServerSettingsManifestBuilder manifestBuilder,
                                            TracingSettingsWriter settingsWriter) {
        this.manifestBuilder = requireNonNull(manifestBuilder, "manifestBuilder");
        this.settingsWriter = requireNonNull(settingsWriter, "settingsWriter");
    }

    SettingsDelivery deliverInitial() {
        return deliver(DeliveryPhase.INITIAL);
    }

    SettingsDelivery refresh() {
        return deliver(DeliveryPhase.REFRESH);
    }

    private synchronized SettingsDelivery deliver(DeliveryPhase phase) {
        CompleteTracingSettings manifest = manifestBuilder.build();
        SettingsRevision base = expectedBase(phase);
        SettingsDelivery delivery = settingsWriter.applySnapshot(base, manifest, SettingsWriter.CONFIG_SERVER);
        logDelivery(phase, base, delivery);
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
                                    SettingsRevision base,
                                    SettingsDelivery delivery) {
        log.atLevel(levelOf(delivery.outcome()))
                .log(
                        "Config Server settings delivery: phase={}, attemptId={}, outcome={}, messageCode={}, base={}",
                        phase, delivery.attemptId(), delivery.outcome(), delivery.messageCode(), base
                );
    }

    private static Level levelOf(TracingSettingsDeliveryResult outcome) {
        return switch (outcome) {
            case APPLIED, UNCHANGED -> Level.INFO;
            case STALE, REJECTED -> Level.WARN;
            case FAILED -> Level.ERROR;
        };
    }

    private enum DeliveryPhase {
        INITIAL,
        REFRESH
    }
}
