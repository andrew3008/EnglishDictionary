package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;

record ExpectedAcceptedState(
        String runtimeInstanceId,
        long acceptedSequence) {

    ExpectedAcceptedState {
        runtimeInstanceId = Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }

    static ExpectedAcceptedState from(AcceptedTracingPolicyView accepted) {
        Objects.requireNonNull(accepted, "accepted");
        return new ExpectedAcceptedState(
                accepted.runtimeInstanceId(),
                accepted.acceptedSequence());
    }
}
