package space.br1440.platform.tracing.autoconfigure.control.policy.delivery;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;

public interface TracingPolicySnapshotApplier {

    /**
     * {@code expectedBase} обязан быть той самой парой (runtimeInstanceId, acceptedSequence),
     * что была прочитана вместе со снапшотом, из которого построен {@code snapshot} —
     * см. {@link space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence#expectedBase()}.
     */
    TracingPolicyDeliveryResult applySnapshot(ExpectedTracingPolicyBase expectedBase,
                                              CompleteTracingPolicySnapshot snapshot,
                                              TracingPolicySource source);
}
