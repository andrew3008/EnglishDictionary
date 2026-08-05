package space.br1440.platform.tracing.autoconfigure.control.policy.read;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;

public record AcceptedSnapshotWithSequence(long acceptedSequence,
                                           String runtimeInstanceId,
                                           CompleteTracingPolicySnapshot snapshot) {
}
