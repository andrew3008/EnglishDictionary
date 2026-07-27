package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * S-01 corrective evidence. Concrete Agent-specific one-shot accepted-policy store.
 *
 * <h2>Relationship to the original spike candidate</h2>
 * {@link S01AcceptedPolicyStore} proved the commit mechanism over a generic payload. This type is
 * the {@code PROPOSED} production contract that the E3 decision fixes: the same mechanism with the
 * type parameter removed, so the payload is always the concrete deeply immutable
 * {@link S01EffectiveTracingPolicy}. There is deliberately no generic form in the recommended
 * production surface.
 * <p>
 * The read mechanism is unchanged and unchangeable — one {@code AtomicReference.get()} — which is
 * why the original hot-path allocation and latency evidence remains valid for this store.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>exactly one state field: {@code final AtomicReference<S01AcceptedPolicyEnvelope>};</li>
 *   <li>{@link #current()} returns the published immutable envelope reference;</li>
 *   <li>{@link #commit} performs exactly one expected-base {@code compareAndSet};</li>
 *   <li>no loop, so no retry; no candidate-builder parameter, so rebase is unrepresentable;</li>
 *   <li>no validation, digest comparison, audit, diagnostics, metrics or logging: {@code APPLIED}
 *       and {@code STALE} are the only outcomes the store owns.</li>
 * </ul>
 * The accepted sequence is advanced here and nowhere else, and only on a successful
 * {@code compareAndSet}, so a second sequence owner cannot exist.
 * <p>
 * Memory model: {@code get()} is a volatile read and {@code compareAndSet} is a volatile write with
 * {@code VarHandle.compareAndSet} semantics, so a successful commit happens-before every subsequent
 * observing read. Combined with the deep immutability of the envelope and the policy, a reader needs
 * no synchronisation and cannot observe a partially constructed object graph.
 * <p>
 * Evidence-only. {@code PROPOSED} name; not an approved production type.
 */
final class S01AcceptedPolicyStateStore {

    private final AtomicReference<S01AcceptedPolicyEnvelope> current;

    S01AcceptedPolicyStateStore(String runtimeInstanceId, S01EffectiveTracingPolicy initialPolicy) {
        this.current = new AtomicReference<>(
                new S01AcceptedPolicyEnvelope(runtimeInstanceId, 0L, initialPolicy));
    }

    /**
     * The accepted state in force. One volatile reference load, no allocation, no lock.
     * <p>
     * This is the hot-path read.
     */
    S01AcceptedPolicyEnvelope current() {
        return current.get();
    }

    /**
     * Installs {@code candidate} if and only if {@code expectedBase} still describes the accepted
     * state, in exactly one attempt.
     * <p>
     * For a given expected base at most one concurrent caller observes
     * {@link S01CommitOutcome#APPLIED}: the winning {@code compareAndSet} replaces the witnessed
     * envelope, so every other caller either fails the pre-check or fails the
     * {@code compareAndSet}, and both paths return {@link S01CommitOutcome#STALE} without touching
     * accepted state.
     *
     * @param expectedBase base the caller claims to have built {@code candidate} on top of
     * @param candidate    fully built deeply immutable policy
     * @return the outcome plus the accepted state in force when the attempt returned, so a loser
     *         learns the fresh base without a second read
     */
    S01ConcreteCommitResult commit(S01ExpectedBase expectedBase, S01EffectiveTracingPolicy candidate) {
        Objects.requireNonNull(expectedBase, "expectedBase");
        Objects.requireNonNull(candidate, "candidate");

        S01AcceptedPolicyEnvelope witnessed = current.get();

        if (!witnessed.runtimeInstanceId().equals(expectedBase.runtimeInstanceId())
                || witnessed.acceptedSequence() != expectedBase.acceptedSequence()) {
            return new S01ConcreteCommitResult(S01CommitOutcome.STALE, witnessed);
        }

        S01AcceptedPolicyEnvelope next = new S01AcceptedPolicyEnvelope(
                witnessed.runtimeInstanceId(),
                witnessed.acceptedSequence() + 1L,
                candidate);

        if (current.compareAndSet(witnessed, next)) {
            return new S01ConcreteCommitResult(S01CommitOutcome.APPLIED, next);
        }

        return new S01ConcreteCommitResult(S01CommitOutcome.STALE, current.get());
    }
}
