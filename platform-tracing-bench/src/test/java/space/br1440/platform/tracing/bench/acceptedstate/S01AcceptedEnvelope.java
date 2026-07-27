package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;

/**
 * S-01 spike type. The single immutable unit of accepted state.
 * <p>
 * Runtime identity, accepted sequence and policy live in <b>one</b> object so that a single
 * reference load yields a logically atomic triple. This is what makes a second
 * {@code AtomicReference} and a sequence counter outside the accepted state unnecessary.
 * <p>
 * Attempt metadata, audit records, source revision and failure details are deliberately absent:
 * they are not part of the compare-and-set payload.
 *
 * @param runtimeInstanceId identity of the agent runtime that owns this accepted state
 * @param acceptedSequence  monotonically increasing accepted sequence, advanced only by the store
 * @param policy            immutable accepted policy
 * @param <S>               immutable policy type
 */
record S01AcceptedEnvelope<S>(String runtimeInstanceId, long acceptedSequence, S policy) {

    S01AcceptedEnvelope {
        Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        Objects.requireNonNull(policy, "policy");
        if (acceptedSequence < 0) {
            throw new IllegalArgumentException("acceptedSequence must not be negative");
        }
    }

    /** The expected base a caller must present to commit on top of this accepted state. */
    S01ExpectedBase expectedBase() {
        return new S01ExpectedBase(runtimeInstanceId, acceptedSequence);
    }
}
