package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;

record AcceptedTracingPolicyView(
        String runtimeInstanceId,
        long acceptedSequence,
        String payloadDigest,
        CompleteTracingPolicySnapshot policy,
        String lastApplyOutcome,
        String lastAttemptId,
        String lastFailureCode,
        boolean structuralDrift,
        boolean restartRequired) {

    AcceptedTracingPolicyView {
        runtimeInstanceId = Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        payloadDigest = Objects.requireNonNull(payloadDigest, "payloadDigest");
        policy = Objects.requireNonNull(policy, "policy");
        lastApplyOutcome = bounded(lastApplyOutcome, 32);
        lastAttemptId = bounded(lastAttemptId, 36);
        lastFailureCode = bounded(lastFailureCode, 64);
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }

    private static String bounded(String value, int maxLength) {
        Objects.requireNonNull(value, "value");
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("diagnostic token exceeds " + maxLength + " characters");
        }
        return value;
    }
}
