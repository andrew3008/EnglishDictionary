package space.br1440.platform.tracing.autoconfigure.support;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;

public final class AcceptedSettingsTestFixtures {

    private AcceptedSettingsTestFixtures() {
    }

    public static AcceptedSettings bootstrapAgentDefaults(long acceptedSequence,
                                                          String agentInstanceId,
                                                          CompleteTracingSettings snapshot) {
        return new AcceptedSettings(
                new SettingsRevision(agentInstanceId, acceptedSequence),
                snapshot,
                AcceptedSettingsAuthority.BOOTSTRAP
        );
    }

    public static AcceptedSettings configServerAccepted(long acceptedSequence,
                                                        String agentInstanceId,
                                                        CompleteTracingSettings snapshot) {
        return new AcceptedSettings(
                new SettingsRevision(agentInstanceId, acceptedSequence),
                snapshot,
                AcceptedSettingsAuthority.CONFIG_SERVER
        );
    }
}
