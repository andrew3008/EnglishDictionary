package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Complete accepted snapshot paired with its acceptedSequence and the
 * Agent-reported runtimeInstanceId.
 * <p>
 * {@code runtimeInstanceId} is decoded from the Agent wire response
 * ({@code readAcceptedComplete} / PRIV-R07) and must NOT be computed
 * or substituted by the caller. It is the authoritative Agent instance
 * identity for restart-detection in optimistic-mutation flows.
 * <p>
 * Used exclusively by the Actuator adapter read-copy-change-apply path.
 * Must NOT be exposed on any public read surface.
 */
public record AcceptedSnapshotWithSequence(
        long acceptedSequence,
        String runtimeInstanceId,
        CompleteTracingPolicySnapshot snapshot
) {}
