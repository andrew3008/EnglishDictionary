package space.br1440.platform.tracing.autoconfigure.control.settings.delivery;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;

public interface TracingSettingsApplier {

    TracingSettingsDeliveryResult applySnapshot(ExpectedSettingsRevision expectedBase,
                                              CompleteTracingSettings snapshot,
                                              TracingSettingsDeliverySource source);

}
