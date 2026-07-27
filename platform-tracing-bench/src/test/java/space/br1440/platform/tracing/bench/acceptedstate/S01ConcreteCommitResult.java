package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;

/**
 * S-01 corrective evidence. Outcome of one {@link S01AcceptedPolicyStateStore#commit} attempt.
 * <p>
 * The non-generic counterpart of {@link S01CommitResult}. {@code accepted} is always the accepted
 * state in force when the attempt returned: the newly installed envelope for
 * {@link S01CommitOutcome#APPLIED}, and the fresher envelope the caller lost to for
 * {@link S01CommitOutcome#STALE}. Returning it is what lets the store stay retry-free — a loser
 * already holds the base it would need to rebuild on, and the decision to rebuild belongs to the
 * caller.
 * <p>
 * Evidence-only. {@code PROPOSED} name; not an approved production type.
 *
 * @param outcome  {@code APPLIED} or {@code STALE} — the only two outcomes the store owns
 * @param accepted accepted state in force when the attempt returned
 */
record S01ConcreteCommitResult(S01CommitOutcome outcome, S01AcceptedPolicyEnvelope accepted) {

    S01ConcreteCommitResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accepted, "accepted");
    }
}
