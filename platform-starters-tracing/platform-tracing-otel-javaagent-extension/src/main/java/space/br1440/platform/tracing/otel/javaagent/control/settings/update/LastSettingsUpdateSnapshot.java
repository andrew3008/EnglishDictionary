package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record LastSettingsUpdateSnapshot(SettingsUpdateAttempt latestAttempt,
                                         SettingsUpdateStatus latestOutcome,
                                         SettingsRejectionReason latestMessageCode,
                                         Timestamps timestamps,
                                         Map<SettingsUpdateStatus, Long> outcomeCounts) {

    public LastSettingsUpdateSnapshot {
        Objects.requireNonNull(timestamps, "timestamps");
        Objects.requireNonNull(outcomeCounts, "outcomeCounts");

        outcomeCounts = outcomeCounts.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(outcomeCounts));
    }

    public record Timestamps(Instant lastAttemptAt,
                             Instant lastSuccessfulEvaluationAt,
                             Instant lastAcceptedChangeAt,
                             Instant lastUnsuccessfulAttemptAt) {

        static Timestamps empty() {
            return new Timestamps(null, null, null, null);
        }

        Timestamps advance(SettingsUpdateStatus outcome, Instant rawObservedAt) {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(rawObservedAt, "rawObservedAt");

            Instant observedAt = (lastAttemptAt != null && rawObservedAt.isBefore(lastAttemptAt))
                    ? lastAttemptAt
                    : rawObservedAt;

            boolean successfulEvaluation = outcome.isSuccessful();
            boolean acceptedChange = outcome == SettingsUpdateStatus.APPLIED;

            Instant nextLastSuccessfulEvaluationAt = successfulEvaluation ? observedAt : lastSuccessfulEvaluationAt;
            Instant nextLastAcceptedChangeAt = acceptedChange ? observedAt : lastAcceptedChangeAt;
            Instant nextLastUnsuccessfulAttemptAt = successfulEvaluation ? lastUnsuccessfulAttemptAt : observedAt;

            return new Timestamps(
                    observedAt,
                    nextLastSuccessfulEvaluationAt,
                    nextLastAcceptedChangeAt,
                    nextLastUnsuccessfulAttemptAt
            );
        }
    }

    static LastSettingsUpdateSnapshot empty() {
        return new LastSettingsUpdateSnapshot(null, null, null, Timestamps.empty(), Map.of());
    }

    public long countOf(SettingsUpdateStatus status) {
        Objects.requireNonNull(status, "status");
        return outcomeCounts.getOrDefault(status, 0L);
    }

    LastSettingsUpdateSnapshot next(SettingsUpdateAttempt attempt, SettingsUpdateResult result) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");
        return next(attempt, result, attempt.observedAt());
    }

    LastSettingsUpdateSnapshot nextWithoutAttempt(SettingsUpdateResult result, Instant observedAt) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(observedAt, "observedAt");
        return next(null, result, observedAt);
    }

    private LastSettingsUpdateSnapshot next(SettingsUpdateAttempt attempt,
                                            SettingsUpdateResult result,
                                            Instant observedAt) {
        Map<SettingsUpdateStatus, Long> nextCounts = new EnumMap<>(SettingsUpdateStatus.class);
        nextCounts.putAll(outcomeCounts);
        nextCounts.merge(result.outcome(), 1L, Long::sum);

        return new LastSettingsUpdateSnapshot(
                attempt,
                result.outcome(),
                result.messageCode(),
                timestamps.advance(result.outcome(), observedAt),
                nextCounts
        );
    }
}
