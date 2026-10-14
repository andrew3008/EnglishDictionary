package space.br1440.platform.tracing.autoconfigure.control.settings;

public interface TracingSettingsWriter extends AcceptedTracingSettingsReader {

    SettingsDelivery applySnapshot(SettingsRevision expectedBase,
                                   CompleteTracingSettings snapshot,
                                   SettingsWriter writer);

}
