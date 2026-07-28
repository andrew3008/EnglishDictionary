package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.time.Instant;

record PolicyApplyDiagnosticsSnapshot(
        PolicyApplyAttempt latestAttempt,
        PolicyApplyOutcome latestOutcome,
        PolicyMessageCode latestMessageCode,
        Instant lastAttemptAt,
        Instant lastSuccessfulEvaluationAt,
        Instant lastAcceptedChangeAt,
        Instant lastUnsuccessfulAttemptAt,
        long appliedCount,
        long unchangedCount,
        long rejectedCount,
        long staleCount,
        long failedCount) {

    static PolicyApplyDiagnosticsSnapshot empty() {
        return new PolicyApplyDiagnosticsSnapshot(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                0L,
                0L);
    }

    PolicyApplyDiagnosticsSnapshot next(
            PolicyApplyAttempt attempt,
            PolicyApplyResult result) {
        boolean successfulEvaluation = result.outcome() == PolicyApplyOutcome.APPLIED
                || result.outcome() == PolicyApplyOutcome.UNCHANGED;
        boolean acceptedChange = result.outcome() == PolicyApplyOutcome.APPLIED;
        boolean unsuccessfulAttempt = result.outcome() == PolicyApplyOutcome.REJECTED
                || result.outcome() == PolicyApplyOutcome.STALE
                || result.outcome() == PolicyApplyOutcome.FAILED;

        return new PolicyApplyDiagnosticsSnapshot(
                attempt,
                result.outcome(),
                result.messageCode(),
                attempt.observedAt(),
                successfulEvaluation ? attempt.observedAt() : lastSuccessfulEvaluationAt,
                acceptedChange ? attempt.observedAt() : lastAcceptedChangeAt,
                unsuccessfulAttempt ? attempt.observedAt() : lastUnsuccessfulAttemptAt,
                appliedCount + increment(result.outcome(), PolicyApplyOutcome.APPLIED),
                unchangedCount + increment(result.outcome(), PolicyApplyOutcome.UNCHANGED),
                rejectedCount + increment(result.outcome(), PolicyApplyOutcome.REJECTED),
                staleCount + increment(result.outcome(), PolicyApplyOutcome.STALE),
                failedCount + increment(result.outcome(), PolicyApplyOutcome.FAILED));
    }

    private static int increment(PolicyApplyOutcome actual, PolicyApplyOutcome expected) {
        return actual == expected ? 1 : 0;
    }
}
