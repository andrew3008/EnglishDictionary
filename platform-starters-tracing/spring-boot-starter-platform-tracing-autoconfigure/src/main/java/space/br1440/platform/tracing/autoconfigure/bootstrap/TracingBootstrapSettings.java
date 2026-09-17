package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.Objects;

public record TracingBootstrapSettings(boolean tracingEnabled,
                                       TracingControlChannel controlChannel) {

    public TracingBootstrapSettings {
        Objects.requireNonNull(controlChannel, "controlChannel");
    }
}
