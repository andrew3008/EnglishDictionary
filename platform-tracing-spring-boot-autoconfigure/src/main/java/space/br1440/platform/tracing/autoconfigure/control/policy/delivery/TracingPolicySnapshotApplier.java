package space.br1440.platform.tracing.autoconfigure.control.policy.delivery;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;

public interface TracingPolicySnapshotApplier {

    TracingPolicyDeliveryResult applySnapshot(long expectedSequence,
                                              CompleteTracingPolicySnapshot snapshot,
                                              TracingPolicySource source);
}
