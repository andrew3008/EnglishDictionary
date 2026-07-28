package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;

record AcceptedPolicyEnvelope(
        String runtimeInstanceId,
        long acceptedSequence,
        EffectiveTracingPolicy policy) implements AcceptedPolicyView {

    AcceptedPolicyEnvelope {
        runtimeInstanceId = RuntimeInstanceIds.requireCanonical(runtimeInstanceId);
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
        Objects.requireNonNull(policy, "policy");
    }

    ExpectedPolicyBase base() {
        return new ExpectedPolicyBase(runtimeInstanceId, acceptedSequence);
    }

    @Override
    public String runtimeInstanceId() {
        return runtimeInstanceId;
    }

    @Override
    public long acceptedSequence() {
        return acceptedSequence;
    }

    @Override
    public boolean samplingEnabled() {
        return policy.samplingEnabled();
    }

    @Override
    public double samplingRatio() {
        return policy.samplingRatio();
    }

    @Override
    public java.util.NavigableMap<String, Double> samplingRouteRatios() {
        return policy.samplingRouteRatios();
    }

    @Override
    public boolean validationEnabled() {
        return policy.validationEnabled();
    }

    @Override
    public boolean validationStrict() {
        return policy.validationStrict();
    }

    @Override
    public boolean exporterEnabled() {
        return policy.exporterEnabled();
    }

    @Override
    public boolean traceControlHeadersEnabled() {
        return policy.traceControlHeadersEnabled();
    }

    @Override
    public String diagnosticsLogLevel() {
        return policy.diagnosticsLogLevel().name();
    }
}
