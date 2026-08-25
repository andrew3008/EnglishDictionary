package space.br1440.platform.tracing.otel.javaagent.control.settings;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;

public record SettingsCommitResult(SettingsCommitOutcome outcome,
                                   TracingSettingsRevision accepted) {

    public SettingsCommitResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accepted, "accepted");
    }
}
