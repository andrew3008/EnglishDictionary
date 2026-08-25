package space.br1440.platform.tracing.otel.javaagent.control.settings.model;

import space.br1440.platform.tracing.otel.javaagent.control.RuntimeInstanceIds;

public record ExpectedSettingsRevision(String runtimeInstanceId, long acceptedSequence) {

    public ExpectedSettingsRevision {
        runtimeInstanceId = RuntimeInstanceIds.requireCanonical(runtimeInstanceId);
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("AcceptedSequence must not be negative");
        }
    }
}
