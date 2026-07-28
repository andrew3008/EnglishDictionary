package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Единственный mutable owner принятого runtime policy snapshot.
 */
final class AcceptedPolicyStateStore {

    private final AtomicReference<AcceptedPolicyEnvelope> current;

    AcceptedPolicyStateStore(String runtimeInstanceId, EffectiveTracingPolicy initialPolicy) {
        this.current = new AtomicReference<>(
                new AcceptedPolicyEnvelope(runtimeInstanceId, 0L, initialPolicy));
    }

    /**
     * Hot-path чтение: один volatile load без allocation, lock и JMX.
     */
    AcceptedPolicyEnvelope current() {
        return current.get();
    }

    /**
     * Выполняет не более одной CAS-попытки и никогда не делает retry или rebase.
     */
    PolicyCommitResult commit(ExpectedPolicyBase expectedBase, EffectiveTracingPolicy candidate) {
        Objects.requireNonNull(expectedBase, "expectedBase");
        Objects.requireNonNull(candidate, "candidate");

        AcceptedPolicyEnvelope witnessed = current.get();
        if (!matches(witnessed, expectedBase)) {
            return new PolicyCommitResult(PolicyCommitOutcome.STALE, witnessed);
        }

        AcceptedPolicyEnvelope next = new AcceptedPolicyEnvelope(
                witnessed.runtimeInstanceId(),
                Math.incrementExact(witnessed.acceptedSequence()),
                candidate);
        if (current.compareAndSet(witnessed, next)) {
            return new PolicyCommitResult(PolicyCommitOutcome.APPLIED, next);
        }
        return new PolicyCommitResult(PolicyCommitOutcome.STALE, current.get());
    }

    private static boolean matches(
            AcceptedPolicyEnvelope envelope,
            ExpectedPolicyBase expectedBase) {
        return envelope.acceptedSequence() == expectedBase.acceptedSequence()
                && envelope.runtimeInstanceId().equals(expectedBase.runtimeInstanceId());
    }
}
