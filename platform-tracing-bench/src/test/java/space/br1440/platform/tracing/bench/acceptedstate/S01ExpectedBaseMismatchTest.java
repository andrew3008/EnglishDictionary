package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate E3 expected-base proofs: both components of the expected base are load-bearing, and a mismatch
 * in either one leaves accepted state and accepted sequence untouched.
 * <p>
 * The runtime-instance component is what makes a base from a previous agent lifetime unusable, so a
 * control plane that cached a sequence across an agent restart cannot silently overwrite the new
 * runtime's accepted policy.
 */
@DisplayName("S-01: expected-base mismatch is STALE and never mutates accepted state")
class S01ExpectedBaseMismatchTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";

    private static S01AcceptedPolicyStore<S01Policy> store() {
        return new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(0L));
    }

    @Test
    @DisplayName("runtimeInstanceId mismatch → STALE, state unchanged, sequence unchanged")
    void runtimeInstanceMismatchIsStale() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01AcceptedEnvelope<S01Policy> before = store.current();
        S01ExpectedBase foreignBase = new S01ExpectedBase("other-runtime", before.acceptedSequence());

        // when
        S01CommitResult<S01Policy> result = store.commit(foreignBase, S01Policy.of(99L));

        // then
        assertThat(result.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(store.current())
                .as("no compare-and-set happened, so the very same envelope instance is still in force")
                .isSameAs(before);
        assertThat(store.current().acceptedSequence()).isEqualTo(before.acceptedSequence());
        assertThat(store.current().policy()).isEqualTo(S01Policy.of(0L));
    }

    @Test
    @DisplayName("acceptedSequence mismatch → STALE, state unchanged")
    void sequenceMismatchIsStale() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        store.commit(store.current().expectedBase(), S01Policy.of(1L));
        S01AcceptedEnvelope<S01Policy> before = store.current();

        // when: a base from before the last accepted commit
        S01CommitResult<S01Policy> result =
                store.commit(new S01ExpectedBase(RUNTIME_INSTANCE_ID, 0L), S01Policy.of(99L));

        // then
        assertThat(result.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(store.current()).isSameAs(before);
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);
        assertThat(store.current().policy()).isEqualTo(S01Policy.of(1L));
    }

    @Test
    @DisplayName("a sequence ahead of accepted state is equally STALE")
    void futureSequenceIsStale() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01AcceptedEnvelope<S01Policy> before = store.current();

        // when
        S01CommitResult<S01Policy> result =
                store.commit(new S01ExpectedBase(RUNTIME_INSTANCE_ID, 42L), S01Policy.of(99L));

        // then
        assertThat(result.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(store.current()).isSameAs(before);
    }

    @Test
    @DisplayName("matching base → APPLIED, sequence +1, identity preserved")
    void matchingBaseIsApplied() {
        // given
        S01AcceptedPolicyStore<S01Policy> store = store();
        S01AcceptedEnvelope<S01Policy> before = store.current();

        // when
        S01CommitResult<S01Policy> result = store.commit(before.expectedBase(), S01Policy.of(7L));

        // then
        assertThat(result.outcome()).isEqualTo(S01CommitOutcome.APPLIED);
        assertThat(store.current().acceptedSequence()).isEqualTo(before.acceptedSequence() + 1L);
        assertThat(store.current().runtimeInstanceId()).isEqualTo(RUNTIME_INSTANCE_ID);
        assertThat(store.current().policy()).isEqualTo(S01Policy.of(7L));
        assertThat(store.current())
                .as("a successful commit always installs a new envelope instance")
                .isNotSameAs(before);
    }
}
