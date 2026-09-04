package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;

public record SettingsStatusSnapshot(
        TracingSettingsRevision accepted,
        LastSettingsUpdateSnapshot update
) {

    public SettingsStatusSnapshot {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(update, "update");
    }
}
