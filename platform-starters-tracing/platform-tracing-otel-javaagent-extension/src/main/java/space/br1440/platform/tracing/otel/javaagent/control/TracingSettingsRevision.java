package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.NavigableMap;
import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

public record TracingSettingsRevision(AgentInstanceId agentInstanceId,
                                      long acceptedSequence,
                                      TracingSettings policy,
                                      SettingsSourceAuthority sourceAuthority) {

    public TracingSettingsRevision {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        Objects.requireNonNull(sourceAuthority, "sourceAuthority");

        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }

    public SettingsRevision base() {
        return new SettingsRevision(agentInstanceId, acceptedSequence);
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

    public SpanAttributeValidationLevel validationLevel() {
        return policy.validationLevel();
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
