package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.Objects;

public record TracingBootstrapSettings(boolean tracingEnabled,
                                       TracingConfigurationAuthority authority) {

    public TracingBootstrapSettings {
        Objects.requireNonNull(authority, "authority");
    }
}
