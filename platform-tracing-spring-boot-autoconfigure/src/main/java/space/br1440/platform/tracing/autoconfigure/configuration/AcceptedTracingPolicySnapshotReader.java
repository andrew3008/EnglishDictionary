package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Optional;

/**
 * Internal complete accepted snapshot read capability.
 * <p>
 * Returns the full {@link CompleteTracingPolicySnapshot} including route ratios.
 * Used exclusively by the Actuator adapter for read-copy-change-apply.
 * <p>
 * This is NOT the public Actuator read surface. The public surface is
 * {@link AcceptedTracingPolicyReader} which returns only the secret-safe
 * {@link AcceptedTracingPolicyView}.
 * <p>
 * Must NOT be exposed as a Spring bean to external consumers.
 * Must NOT be wired into the read Actuator endpoint.
 */
public interface AcceptedTracingPolicySnapshotReader {
    /**
     * Returns the current complete accepted snapshot with all eight leaves.
     * Returns empty if Agent is not reachable or no snapshot has been applied.
     */
    Optional<AcceptedSnapshotWithSequence> currentComplete();
}
