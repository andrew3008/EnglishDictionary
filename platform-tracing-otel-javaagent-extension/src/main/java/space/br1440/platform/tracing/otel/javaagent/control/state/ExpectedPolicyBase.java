package space.br1440.platform.tracing.otel.javaagent.control.state;

record ExpectedPolicyBase(String runtimeInstanceId, long acceptedSequence) {

    ExpectedPolicyBase {
        runtimeInstanceId = RuntimeInstanceIds.requireCanonical(runtimeInstanceId);
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }
}
