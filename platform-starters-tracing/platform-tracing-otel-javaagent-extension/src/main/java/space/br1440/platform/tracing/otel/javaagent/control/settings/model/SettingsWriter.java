package space.br1440.platform.tracing.otel.javaagent.control.settings.model;

import java.util.Objects;
import java.util.Set;

public enum SettingsWriter {

    CONFIG_SERVER(SettingsSourceAuthority.CONFIG_SERVER),
    ACTUATOR(SettingsSourceAuthority.ACTUATOR);

    private final SettingsSourceAuthority authority;

    SettingsWriter(SettingsSourceAuthority authority) {
        this.authority = authority;
    }

    public SettingsSourceAuthority authority() {
        return authority;
    }

    public boolean admits(Set<PolicyLeaf> changed) {
        Objects.requireNonNull(changed, "changed");
        return this == CONFIG_SERVER || changed.size() <= 1;
    }
}
