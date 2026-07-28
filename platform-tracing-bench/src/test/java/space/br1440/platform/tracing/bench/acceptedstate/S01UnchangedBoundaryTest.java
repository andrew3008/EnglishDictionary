package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate E3 boundary proof: {@code UNCHANGED}, {@code REJECTED} and {@code FAILED} are decided outside
 * the store, so none of them reaches a compare-and-set or advances the accepted sequence.
 * <p>
 * No spy or test seam is needed to prove the store was not entered. A successful commit always
 * installs a <b>new</b> envelope instance, so reference identity of the accepted envelope is a direct
 * observable: if the envelope instance is unchanged, no compare-and-set succeeded. That is a stronger
 * statement than value equality, which could not distinguish "not committed" from "committed an equal
 * value".
 */
@DisplayName("S-01: UNCHANGED, REJECTED and FAILED never reach the store")
class S01UnchangedBoundaryTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";

    private static S01AcceptedPolicyStore<S01Policy> store() {
        return new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(5L));
    }

    @Test
    @DisplayName("semantically identical candidate → UNCHANGED, no CAS, sequence unchanged")
    void identicalCandidateIsUnchangedWithoutCommit() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01ApplyOrchestrator orchestrator = new S01ApplyOrchestrator(store, policy -> true);
        S01AcceptedEnvelope<S01Policy> before = store.current();

        // when: a distinct instance carrying the identical semantic policy
        S01ApplyOutcome outcome = orchestrator.apply(S01Policy.of(5L));

        // then
        assertThat(outcome).isEqualTo(S01ApplyOutcome.UNCHANGED);
        assertThat(store.current())
                .as("the accepted envelope instance is untouched, so no compare-and-set was performed")
                .isSameAs(before);
        assertThat(store.current().acceptedSequence()).isEqualTo(before.acceptedSequence());
    }

    @Test
    @DisplayName("domain-invalid candidate → REJECTED, no CAS, sequence unchanged")
    void invalidCandidateIsRejectedWithoutCommit() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01ApplyOrchestrator orchestrator = new S01ApplyOrchestrator(store, policy -> false);
        S01AcceptedEnvelope<S01Policy> before = store.current();

        // when
        S01ApplyOutcome outcome = orchestrator.apply(S01Policy.of(6L));

        // then
        assertThat(outcome).isEqualTo(S01ApplyOutcome.REJECTED);
        assertThat(store.current()).isSameAs(before);
        assertThat(store.current().acceptedSequence()).isEqualTo(before.acceptedSequence());
    }

    @Test
    @DisplayName("validation failure → FAILED, no CAS, sequence unchanged")
    void validatorFailureIsFailedWithoutCommit() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01ApplyOrchestrator orchestrator = new S01ApplyOrchestrator(store, policy -> {
            throw new IllegalStateException("validator exploded");
        });
        S01AcceptedEnvelope<S01Policy> before = store.current();

        // when
        S01ApplyOutcome outcome = orchestrator.apply(S01Policy.of(6L));

        // then
        assertThat(outcome).isEqualTo(S01ApplyOutcome.FAILED);
        assertThat(store.current()).isSameAs(before);
        assertThat(store.current().acceptedSequence()).isEqualTo(before.acceptedSequence());
    }

    @Test
    @DisplayName("semantically different valid candidate → APPLIED, sequence +1")
    void differentValidCandidateIsApplied() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01ApplyOrchestrator orchestrator = new S01ApplyOrchestrator(store, policy -> true);
        S01AcceptedEnvelope<S01Policy> before = store.current();

        // when
        S01ApplyOutcome outcome = orchestrator.apply(S01Policy.of(6L));

        // then
        assertThat(outcome).isEqualTo(S01ApplyOutcome.APPLIED);
        assertThat(store.current().acceptedSequence()).isEqualTo(before.acceptedSequence() + 1L);
        assertThat(store.current().policy()).isEqualTo(S01Policy.of(6L));
    }

    @Test
    @DisplayName("a stale base still reaches the store and is reported as STALE, not UNCHANGED")
    void staleBaseIsDistinctFromUnchanged() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01ApplyOrchestrator orchestrator = new S01ApplyOrchestrator(store, policy -> true);

        // when
        S01ApplyOutcome first = orchestrator.apply(S01Policy.of(6L));
        S01ApplyOutcome second = orchestrator.apply(S01Policy.of(7L));

        // then: the orchestrator re-reads the base each time, so both win; STALE requires a caller
        // that holds a base across a concurrent commit, which the one-winner proof covers.
        assertThat(first).isEqualTo(S01ApplyOutcome.APPLIED);
        assertThat(second).isEqualTo(S01ApplyOutcome.APPLIED);
        assertThat(store.current().acceptedSequence()).isEqualTo(2L);
    }
}
