package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;

/**
 * S-01 corrective evidence. Concrete, non-generic accepted-state envelope.
 * <p>
 * Identical in shape to the generic {@link S01AcceptedEnvelope} used by the original mechanism
 * proofs, with one deliberate difference: the policy component is the concrete deeply immutable
 * {@link S01EffectiveTracingPolicy} rather than an arbitrary type parameter. That removes the
 * hole the generic form left open, namely that a caller could instantiate the store with a mutable
 * payload and still satisfy the compiler.
 * <p>
 * All three components live in one object, so one reference load yields a logically atomic triple
 * and no second atomic is needed for the sequence. Attempt metadata, audit trail, source revision
 * and failure detail remain outside the compare-and-set payload.
 * <p>
 * Evidence-only. {@code PROPOSED} name; not an approved production type.
 *
 * @param runtimeInstanceId identity of the Agent runtime that owns this accepted state
 * @param acceptedSequence  monotonic accepted sequence, advanced only by
 *                          {@link S01AcceptedPolicyStateStore}
 * @param policy            deeply immutable accepted policy carrying exactly eight Category A leaves
 */
record S01AcceptedPolicyEnvelope(String runtimeInstanceId,
                                 long acceptedSequence,
                                 S01EffectiveTracingPolicy policy) {

    S01AcceptedPolicyEnvelope {
        Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        Objects.requireNonNull(policy, "policy");
        if (acceptedSequence < 0L) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }

    /** The expected base a caller must present to commit on top of this accepted state. */
    S01ExpectedBase expectedBase() {
        return new S01ExpectedBase(runtimeInstanceId, acceptedSequence);
    }
}
