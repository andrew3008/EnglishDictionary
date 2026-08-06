package space.br1440.platform.tracing.autoconfigure.control.policy.delivery;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;

public interface TracingPolicySnapshotApplier {

    TracingPolicyDeliveryResult applySnapshot(ExpectedTracingPolicyBase expectedBase,
                                              CompleteTracingPolicySnapshot snapshot,
                                              TracingPolicySource source);

}
