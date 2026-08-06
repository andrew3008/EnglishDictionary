package space.br1440.platform.tracing.autoconfigure.control.policy;

public record ExpectedTracingPolicyBase(String runtimeInstanceId, long acceptedSequence) {

    public ExpectedTracingPolicyBase {
        if (runtimeInstanceId == null || runtimeInstanceId.isBlank()) {
            throw new IllegalArgumentException("runtimeInstanceId must not be blank");
        }

        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must be >= 0, got " + acceptedSequence);
        }
    }
}
