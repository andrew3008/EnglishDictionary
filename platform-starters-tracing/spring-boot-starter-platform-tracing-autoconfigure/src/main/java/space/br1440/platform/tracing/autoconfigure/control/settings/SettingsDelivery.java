package space.br1440.platform.tracing.autoconfigure.control.settings;

import java.util.Objects;

public record SettingsDelivery(String attemptId,
                               TracingSettingsDeliveryResult outcome,
                               String messageCode) {

    public SettingsDelivery {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(messageCode, "messageCode");
    }
}
