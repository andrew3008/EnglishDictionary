package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record LastSettingsUpdateSnapshot(SettingsUpdateAttempt latestAttempt,
                                         SettingsUpdateStatus latestOutcome,
                                         SettingsRejectionReason latestMessageCode,
                                         Map<SettingsUpdateStatus, Long> outcomeCounts) {

    public LastSettingsUpdateSnapshot {
        Objects.requireNonNull(outcomeCounts, "outcomeCounts");

        outcomeCounts = outcomeCounts.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(outcomeCounts));
    }

    static LastSettingsUpdateSnapshot empty() {
        return new LastSettingsUpdateSnapshot(null, null, null, Map.of());
    }

    public long countOf(SettingsUpdateStatus status) {
        Objects.requireNonNull(status, "status");

        return outcomeCounts.getOrDefault(status, 0L);
    }

    LastSettingsUpdateSnapshot next(SettingsUpdateAttempt attempt, SettingsUpdateResult result) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");

        return next(attempt, result.outcome(), result.messageCode());
    }

    LastSettingsUpdateSnapshot nextWithoutAttempt(SettingsUpdateResult result) {
        Objects.requireNonNull(result, "result");

        return next(null, result.outcome(), result.messageCode());
    }

    private LastSettingsUpdateSnapshot next(SettingsUpdateAttempt attempt,
                                            SettingsUpdateStatus outcome,
                                            SettingsRejectionReason messageCode) {
        Map<SettingsUpdateStatus, Long> nextCounts = new EnumMap<>(SettingsUpdateStatus.class);
        nextCounts.putAll(outcomeCounts);
        nextCounts.merge(outcome, 1L, Long::sum);

        return new LastSettingsUpdateSnapshot(attempt, outcome, messageCode, nextCounts);
    }
}
