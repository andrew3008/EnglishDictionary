package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.NavigableMap;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

public record TracingSettingsRevision(String runtimeInstanceId,
                                      long acceptedSequence,
                                      TracingSettings policy) {

    public TracingSettingsRevision {
        Objects.requireNonNull(policy, "policy");

        runtimeInstanceId = RuntimeInstanceIds.requireCanonical(runtimeInstanceId);
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }

    public ExpectedSettingsRevision base() {
        return new ExpectedSettingsRevision(runtimeInstanceId, acceptedSequence);
    }

    public boolean samplingEnabled() {
        return policy.samplingEnabled();
    }

    public double samplingRatio() {
        return policy.samplingRatio();
    }

    public NavigableMap<String, Double> samplingRouteRatios() {
        return policy.samplingRouteRatios();
    }

    public boolean validationEnabled() {
        return policy.validationEnabled();
    }

    public boolean validationStrict() {
        return policy.validationStrict();
    }

    public boolean exporterEnabled() {
        return policy.exporterEnabled();
    }

    public boolean traceControlHeadersEnabled() {
        return policy.traceControlHeadersEnabled();
    }

    public String diagnosticsLogLevel() {
        return policy.diagnosticsLogLevel().name();
    }
}
