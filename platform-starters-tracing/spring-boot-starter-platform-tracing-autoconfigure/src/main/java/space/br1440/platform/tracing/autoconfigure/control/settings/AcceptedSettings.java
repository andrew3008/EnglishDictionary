package space.br1440.platform.tracing.autoconfigure.control.settings;

import java.util.Objects;

public record AcceptedSettings(SettingsRevision revision,
                               CompleteTracingSettings policy,
                               AcceptedSettingsAuthority authority) {

    public AcceptedSettings {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(authority, "authority");
    }
}
