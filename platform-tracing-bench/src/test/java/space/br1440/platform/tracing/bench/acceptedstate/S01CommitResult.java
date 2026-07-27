package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;

/**
 * S-01 spike type. Outcome of one single-shot commit attempt.
 * <p>
 * {@link #accepted()} is the accepted state that is in force when the attempt returns: the
 * candidate for {@link S01CommitOutcome#APPLIED}, and the state that defeated this caller for
 * {@link S01CommitOutcome#STALE}. Returning it lets a loser learn the fresh base without a second
 * read, which is why the store itself never needs to retry.
 *
 * @param outcome  whether this caller installed the candidate
 * @param accepted accepted state in force when the attempt returned
 * @param <S>      immutable policy type
 */
record S01CommitResult<S>(S01CommitOutcome outcome, S01AcceptedEnvelope<S> accepted) {

    S01CommitResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(accepted, "accepted");
    }

    boolean applied() {
        return outcome == S01CommitOutcome.APPLIED;
    }
}
