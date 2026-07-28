package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;

record TracingPolicyDeliveryResult(
        TracingPolicyDeliveryOutcome outcome,
        String attemptId,
        String messageCode,
        long acceptedSequence) {

    TracingPolicyDeliveryResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        attemptId = bounded(attemptId, 36);
        messageCode = bounded(messageCode, 64);
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
