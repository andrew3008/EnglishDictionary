package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Complete accepted snapshot paired with its acceptedSequence.
 * Used exclusively by {@link space.br1440.platform.tracing.autoconfigure.configuration.actuator.SpringActuatorTracingPolicyAdapter}
 * for read-copy-change-apply. Carries full route ratios.
 */
public record AcceptedSnapshotWithSequence(
        long acceptedSequence,
        CompleteTracingPolicySnapshot snapshot
) {}
