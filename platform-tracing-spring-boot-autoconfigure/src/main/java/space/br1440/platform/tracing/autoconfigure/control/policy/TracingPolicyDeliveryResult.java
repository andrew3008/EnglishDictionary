package space.br1440.platform.tracing.autoconfigure.control.policy;

/**
 * Result of a single policy apply attempt through the private JMX boundary.
 * Maps 1:1 to Agent accepted-state outcomes.
 */
public enum TracingPolicyDeliveryResult {
    /** Snapshot accepted; Agent acceptedSequence incremented. */
    APPLIED,
    /** Snapshot semantically identical to current accepted state; no change. */
    UNCHANGED,
    /** Snapshot rejected by Agent (invalid, incomplete, forbidden). */
    REJECTED,
    /** Precondition mismatch: runtimeInstanceId or acceptedSequence changed concurrently. No state change. No retry. */
    STALE,
    /** Internal handler/codec/invariant failure. */
    FAILED
}
