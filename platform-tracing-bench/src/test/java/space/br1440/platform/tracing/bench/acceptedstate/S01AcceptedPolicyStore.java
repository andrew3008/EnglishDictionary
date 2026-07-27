package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * S-01 spike candidate. Single-shot expected-base accepted-state store.
 * <p>
 * The whole point of this type is what it does <b>not</b> do. It holds one immutable
 * {@link S01AcceptedEnvelope} in one {@link AtomicReference} and performs exactly one
 * {@code compareAndSet} per {@link #commit} call:
 * <ul>
 *   <li>no loop, so no retry;</li>
 *   <li>no candidate-builder function, so rebase is not merely forbidden but unrepresentable —
 *       the candidate arrives already built and the store has no way to rebuild it;</li>
 *   <li>no merge, because a losing caller is told {@link S01CommitOutcome#STALE} and the decision
 *       to recompute belongs to the caller;</li>
 *   <li>no validation, no digest comparison, no audit, no diagnostics and no metrics, so the store
 *       stays a pure atomic-commit primitive.</li>
 * </ul>
 * The accepted sequence is advanced here and nowhere else, and only on a successful
 * {@code compareAndSet}. Callers cannot set it, so no second version owner can exist.
 * <p>
 * Memory model: {@code AtomicReference.get()} is a volatile read and {@code compareAndSet} is a
 * volatile write with the semantics of {@code VarHandle.compareAndSet}. The successful commit
 * therefore happens-before every subsequent {@link #current()} that observes the new envelope, so a
 * reader sees a fully constructed envelope and a fully constructed policy without any reader-side
 * synchronisation.
 * <p>
 * Evidence-only: not an approved production name, and not to be referenced from production source
 * sets.
 *
 * @param <S> immutable policy type
 */
final class S01AcceptedPolicyStore<S> {

    private final AtomicReference<S01AcceptedEnvelope<S>> accepted;

    S01AcceptedPolicyStore(String runtimeInstanceId, S initialPolicy) {
        this.accepted = new AtomicReference<>(
                new S01AcceptedEnvelope<>(runtimeInstanceId, 0L, initialPolicy));
    }

    /**
     * The accepted state in force. One volatile reference load, no allocation, no lock.
     * <p>
     * This is the hot-path read.
     */
    S01AcceptedEnvelope<S> current() {
        return accepted.get();
    }

    /**
     * Installs {@code candidate} if and only if {@code expectedBase} still describes the accepted
     * state, in exactly one attempt.
     * <p>
     * For a given expected base at most one concurrent caller can observe
     * {@link S01CommitOutcome#APPLIED}: the winning {@code compareAndSet} replaces the witnessed
     * envelope, so every other caller either fails the pre-check or fails the
     * {@code compareAndSet}, and both paths return {@link S01CommitOutcome#STALE} without touching
     * accepted state.
     *
     * @param expectedBase base the caller claims to have built {@code candidate} on top of
     * @param candidate    fully built immutable policy
     * @return the outcome plus the accepted state in force when the attempt returned
     */
    S01CommitResult<S> commit(S01ExpectedBase expectedBase, S candidate) {
        Objects.requireNonNull(expectedBase, "expectedBase");
        Objects.requireNonNull(candidate, "candidate");

        S01AcceptedEnvelope<S> witnessed = accepted.get();

        if (!witnessed.runtimeInstanceId().equals(expectedBase.runtimeInstanceId())
                || witnessed.acceptedSequence() != expectedBase.acceptedSequence()) {
            return new S01CommitResult<>(S01CommitOutcome.STALE, witnessed);
        }

        S01AcceptedEnvelope<S> next = new S01AcceptedEnvelope<>(
                witnessed.runtimeInstanceId(),
                witnessed.acceptedSequence() + 1L,
                candidate);

        if (accepted.compareAndSet(witnessed, next)) {
            return new S01CommitResult<>(S01CommitOutcome.APPLIED, next);
        }

        return new S01CommitResult<>(S01CommitOutcome.STALE, accepted.get());
    }
}
