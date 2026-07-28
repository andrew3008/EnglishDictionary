package space.br1440.platform.tracing.autoconfigure.configuration;

interface TracingPolicySnapshotClient {

    TracingPolicyDeliveryResult applySnapshot(
            ExpectedAcceptedState expectedState,
            CompleteTracingPolicySnapshot snapshot,
            TracingPolicySource source,
            TracingPolicyApplyTrigger trigger,
            String sourceRevision);
}
