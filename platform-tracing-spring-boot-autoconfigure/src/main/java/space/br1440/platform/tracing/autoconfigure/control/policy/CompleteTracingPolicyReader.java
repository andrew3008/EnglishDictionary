package space.br1440.platform.tracing.autoconfigure.control.policy;

import java.util.Optional;

/**
 * Internal complete accepted snapshot read capability.
 * <p>
 * Backed by the Agent via the approved E7/P0 private JMX operation
 * {@code readAcceptedComplete}. This is the only approved source of
 * complete accepted policy for the Actuator adapter read-copy-change-apply.
 * <p>
 * E7/P0 contract extension (Variant A, formally approved in this commit):
 * <pre>
 *   Previous: 6 marker reads + 1 mutation (applySnapshot)
 *   Added:    1 complete-snapshot read (readAcceptedComplete)
 *   Total:    6 marker reads + 1 complete read + 1 mutation
 * </pre>
 * Wire response: CompositeData with fields {acceptedSequence: long, snapshot: CompositeData}
 * <p>
 * NOT the public Actuator read surface. The public surface is
 * {@link AcceptedTracingPolicyReader} (secret-safe projection).
 * Must NOT be wired into the public read Actuator endpoint.
 */
public interface CompleteTracingPolicyReader {
    /**
     * Returns the current complete accepted snapshot from Agent.
     * Returns empty if Agent is unreachable or no snapshot has been applied.
     * <p>
     * Sequence in the returned value is the Agent-authoritative acceptedSequence.
     * Application code must NOT compute or cache sequence independently.
     */
    Optional<AcceptedSnapshotWithSequence> currentComplete();
}
