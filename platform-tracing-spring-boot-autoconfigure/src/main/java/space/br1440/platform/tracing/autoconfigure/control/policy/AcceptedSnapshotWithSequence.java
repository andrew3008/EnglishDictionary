package space.br1440.platform.tracing.autoconfigure.control.policy;

public record AcceptedSnapshotWithSequence(long acceptedSequence,
                                           String runtimeInstanceId,
                                           CompleteTracingPolicySnapshot snapshot) {
}
