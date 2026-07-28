package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;

record AcceptedPolicyEnvelope(
        String runtimeInstanceId,
        long acceptedSequence,
        EffectiveTracingPolicy policy) {

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
}
