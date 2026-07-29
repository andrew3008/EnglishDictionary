package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Map;
import java.util.UUID;

/**
 * White-box bridge for P5.7 JMH benchmarks.
 *
 * <p>The bridge keeps the production store package-private and exposes only deterministic
 * benchmark operations. Every method verifies that the measured invocation reaches the outcome
 * named by the benchmark.
 */
public final class AcceptedPolicyBenchmarkAccess {

    private static final PolicyLogLevel LOG_LEVEL = PolicyLogLevel.INFO;

    private final AcceptedPolicyStateStore store;
    private final EffectiveTracingPolicy firstCandidate;
    private final EffectiveTracingPolicy secondCandidate;
    private final ExpectedPolicyBase staleBase;

    private AcceptedPolicyBenchmarkAccess(boolean prepareStaleBase) {
        EffectiveTracingPolicy initial = policy(1.0d);
        this.firstCandidate = policy(0.5d);
        this.secondCandidate = policy(0.75d);
        this.store = new AcceptedPolicyStateStore(UUID.randomUUID().toString(), initial);
        this.staleBase = store.current().base();
        if (prepareStaleBase) {
            requireOutcome(
                    store.commit(staleBase, firstCandidate),
                    PolicyCommitOutcome.APPLIED);
        }
    }

    public static AcceptedPolicyBenchmarkAccess appliedPath() {
        return new AcceptedPolicyBenchmarkAccess(false);
    }

    public static AcceptedPolicyBenchmarkAccess stalePath() {
        return new AcceptedPolicyBenchmarkAccess(true);
    }

    public AcceptedPolicyUpdateOutcome applyNext() {
        AcceptedPolicyEnvelope current = store.current();
        EffectiveTracingPolicy candidate =
                current.policy().samplingRatio() == firstCandidate.samplingRatio()
                        ? secondCandidate
                        : firstCandidate;
        requireOutcome(
                store.commit(current.base(), candidate),
                PolicyCommitOutcome.APPLIED);
        return AcceptedPolicyUpdateOutcome.APPLIED;
    }

    public AcceptedPolicyUpdateOutcome applyStale() {
        requireOutcome(
                store.commit(staleBase, secondCandidate),
                PolicyCommitOutcome.STALE);
        return AcceptedPolicyUpdateOutcome.STALE;
    }

    private static EffectiveTracingPolicy policy(double ratio) {
        return EffectiveTracingPolicy.of(
                true,
                ratio,
                Map.of(),
                true,
                false,
                true,
                true,
                LOG_LEVEL);
    }

    private static void requireOutcome(
            PolicyCommitResult result,
            PolicyCommitOutcome expected) {
        if (result.outcome() != expected) {
            throw new IllegalStateException(
                    "Benchmark path drift: expected " + expected + ", got " + result.outcome());
        }
    }
}
