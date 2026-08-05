package space.br1440.platform.tracing.autoconfigure.control.policy;

public interface TracingPolicySnapshotApplier {

    TracingPolicyDeliveryResult applySnapshot(long expectedSequence,
                                              CompleteTracingPolicySnapshot snapshot,
                                              TracingPolicySource source);
}
