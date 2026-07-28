package space.br1440.platform.tracing.bench.acceptedstate;

/**
 * S-01 spike type. The complete set of outcomes an atomic accepted-state commit can produce.
 * <p>
 * The store deliberately knows only these two. {@code UNCHANGED}, {@code REJECTED} and
 * {@code FAILED} are decided by the orchestration layer before the store is reached, which keeps
 * the store a pure atomic-commit primitive.
 */
enum S01CommitOutcome {

    /** The expected base matched the witnessed accepted state and this caller installed the candidate. */
    APPLIED,

    /** The expected base did not match the witnessed accepted state; accepted state is untouched. */
    STALE
}
