package space.br1440.platform.tracing.bench.acceptedstate;

/**
 * S-01 spike type. Full outcome set an operator-visible apply attempt can produce.
 * <p>
 * Only {@link #APPLIED} and {@link #STALE} come from the store. The other three are decided by the
 * orchestration layer around the store, which is the boundary S-01 has to demonstrate.
 */
enum S01ApplyOutcome {

    /** Domain-valid, semantically different, and this caller won the expected-base commit. */
    APPLIED,

    /** Domain-valid but semantically identical to accepted state; the store is never reached. */
    UNCHANGED,

    /** Domain validation refused the candidate; the store is never reached. */
    REJECTED,

    /** The store refused the expected base. */
    STALE,

    /** Building or validating the candidate threw; the store is never reached. */
    FAILED
}
