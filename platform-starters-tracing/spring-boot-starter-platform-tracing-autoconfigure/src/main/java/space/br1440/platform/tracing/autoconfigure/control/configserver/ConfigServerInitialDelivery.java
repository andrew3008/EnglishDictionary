package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static java.util.Objects.requireNonNull;

import org.springframework.context.SmartLifecycle;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;

public final class ConfigServerInitialDelivery implements SmartLifecycle {

    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private final ConfigServerTracingSettingsSynchronizer synchronizer;
    private volatile boolean running;

    ConfigServerInitialDelivery(ConfigServerTracingSettingsSynchronizer synchronizer) {
        this.synchronizer = requireNonNull(synchronizer, "synchronizer");
    }

    @Override
    public void start() {
        SettingsDelivery delivery = synchronizer.deliverInitial();
        if (!isSuccessful(delivery.outcome())) {
            throw new TracingConfigurationGuardException(
                    "[CONFIG_SERVER startup] initial settings delivery failed: outcome=%s, attemptId=%s, messageCode=%s"
                            .formatted(delivery.outcome(), delivery.attemptId(), delivery.messageCode()));
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private static boolean isSuccessful(TracingSettingsDeliveryResult outcome) {
        return outcome == TracingSettingsDeliveryResult.APPLIED
                || outcome == TracingSettingsDeliveryResult.UNCHANGED;
    }
}
