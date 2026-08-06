package space.br1440.platform.tracing.autoconfigure.control.policy.read;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;

public record AcceptedSnapshotWithSequence(long acceptedSequence,
                                           String runtimeInstanceId,
                                           CompleteTracingPolicySnapshot snapshot) {

    public ExpectedTracingPolicyBase expectedBase() {
        return new ExpectedTracingPolicyBase(runtimeInstanceId, acceptedSequence);
    }

}
