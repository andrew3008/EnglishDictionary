package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * S-01 spike harness. Minimal orchestration layer around {@link S01AcceptedPolicyStore}, written to
 * demonstrate where each responsibility that is <b>not</b> atomic commit actually lives.
 * <p>
 * The ordering below is the load-bearing claim: domain validation and semantic-digest comparison both
 * happen <b>before</b> the store is reached, so {@code REJECTED}, {@code UNCHANGED} and
 * {@code FAILED} never reach a compare-and-set and never advance the accepted sequence. The store is
 * left owning nothing but the atomic swap.
 * <p>
 * Semantic digest comparison uses record equality on the candidate policy, which compares exactly
 * the accepted policy fields and nothing else. Attempt metadata is not part of the comparison because
 * it is not part of the accepted state.
 */
final class S01ApplyOrchestrator {

    private final S01AcceptedPolicyStore<S01Policy> store;
    private final Predicate<S01Policy> domainValidator;

    S01ApplyOrchestrator(S01AcceptedPolicyStore<S01Policy> store, Predicate<S01Policy> domainValidator) {
        this.store = Objects.requireNonNull(store, "store");
        this.domainValidator = Objects.requireNonNull(domainValidator, "domainValidator");
    }

    /**
     * Applies {@code candidate} against the currently accepted base.
     *
     * @param candidate fully built immutable candidate policy
     * @return the outcome; only {@link S01ApplyOutcome#APPLIED} and {@link S01ApplyOutcome#STALE}
     *         involve the store
     */
    S01ApplyOutcome apply(S01Policy candidate) {
        S01AcceptedEnvelope<S01Policy> base = store.current();

        boolean valid;
        try {
            valid = domainValidator.test(candidate);
        } catch (RuntimeException e) {
            return S01ApplyOutcome.FAILED;
        }

        if (!valid) {
            return S01ApplyOutcome.REJECTED;
        }

        if (base.policy().equals(candidate)) {
            return S01ApplyOutcome.UNCHANGED;
        }

        S01CommitResult<S01Policy> result = store.commit(base.expectedBase(), candidate);
        return result.applied() ? S01ApplyOutcome.APPLIED : S01ApplyOutcome.STALE;
    }
}
