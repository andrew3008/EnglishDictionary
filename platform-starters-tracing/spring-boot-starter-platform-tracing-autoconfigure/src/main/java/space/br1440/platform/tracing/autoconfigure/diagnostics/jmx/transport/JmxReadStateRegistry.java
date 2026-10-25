package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class JmxReadStateRegistry {

    private final Clock clock;
    private final long warnRateLimitMillis;

    private final ConcurrentHashMap<JmxContractPoint, JmxPointReadState> pointStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<JmxDiagnosticKey, LongAdder> lifetimeCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<JmxDiagnosticKey, LongAdder> suppressedWarnCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<JmxDiagnosticKey, DiagnosticEpisode> episodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<JmxDiagnosticKey, AtomicLong> lastWarnEpochMillis = new ConcurrentHashMap<>();

    JmxReadStateRegistry(Clock clock, Duration warnRateLimit) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.warnRateLimitMillis = Objects.requireNonNull(warnRateLimit, "warnRateLimit").toMillis();
    }

    void recordPresent(JmxContractPoint point, long sequence, Instant observedAt) {
        applyPointState(point, sequence, observedAt, true);
    }

    void recordAbsent(JmxContractPoint point, long sequence, Instant observedAt) {
        applyPointState(point, sequence, observedAt, false);
    }

    void recordContractViolation(JmxContractPoint point, JmxDiagnosticKind kind, long sequence, Instant observedAt) {
        recordDiagnostic(point, JmxDiagnosticCategory.CONTRACT_VIOLATION, kind, sequence, observedAt);
        applyPointState(point, sequence, observedAt, false);
    }

    void recordExecutionFailure(JmxContractPoint point, JmxDiagnosticKind kind, long sequence, Instant observedAt) {
        recordDiagnostic(point, JmxDiagnosticCategory.EXECUTION_FAILURE, kind, sequence, observedAt);
        applyPointState(point, sequence, observedAt, false);
    }

    JmxPointReadState pointState(JmxContractPoint point) {
        return pointStates.getOrDefault(point, JmxPointReadState.NEVER_READ);
    }

    Optional<Duration> lastSuccessAge(JmxContractPoint point) {
        Instant lastSuccessAt = pointState(point).lastSuccessAt();
        return lastSuccessAt == null ? Optional.empty() : Optional.of(Duration.between(lastSuccessAt, clock.instant()));
    }

    List<JmxDiagnosticSnapshot> diagnosticSnapshot() {
        List<JmxDiagnosticSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<JmxDiagnosticKey, DiagnosticEpisode> entry : episodes.entrySet()) {
            JmxDiagnosticKey key = entry.getKey();
            DiagnosticEpisode episode = entry.getValue();
            long lastSuccessSequence = pointState(key.point()).lastSuccessSequence();
            long lifetimeCount = lifetimeCounts.getOrDefault(key, EMPTY_ADDER).sum();
            long suppressedCount = suppressedWarnCounts.getOrDefault(key, EMPTY_ADDER).sum();
            boolean unrecovered = episode.failureSequence() > lastSuccessSequence;

            snapshots.add(
                    new JmxDiagnosticSnapshot(
                            key.point(), key.category(), key.kind(), lifetimeCount,
                            suppressedCount, episode.firstSeenAt(), episode.lastSeenAt(),
                            pointState(key.point()).lastAttemptAt(), unrecovered
                    )
            );
        }

        snapshots.sort(Comparator
                .comparing(JmxDiagnosticSnapshot::point)
                .thenComparing(JmxDiagnosticSnapshot::category)
                .thenComparing(JmxDiagnosticSnapshot::kind));

        return List.copyOf(snapshots);
    }

    private static final LongAdder EMPTY_ADDER = new LongAdder();

    private void applyPointState(JmxContractPoint point, long sequence, Instant observedAt, boolean success) {
        pointStates.compute(point, (ignoredPoint, previous) -> {
            if (previous != null && sequence < previous.lastAppliedSequence()) {
                return previous;
            }

            Instant lastSuccessAt = success ? observedAt : (previous == null ? null : previous.lastSuccessAt());
            long lastSuccessSequence = success ? sequence : (previous == null ? -1L : previous.lastSuccessSequence());
            return new JmxPointReadState(sequence, lastSuccessSequence, observedAt, lastSuccessAt);
        });
    }

    private void recordDiagnostic(JmxContractPoint point, JmxDiagnosticCategory category, JmxDiagnosticKind kind, long sequence, Instant observedAt) {
        JmxDiagnosticKey key = new JmxDiagnosticKey(point, category, kind);
        lifetimeCounts.computeIfAbsent(key, ignored -> new LongAdder()).increment();

        boolean[] episodeOpened = new boolean[1];
        episodes.compute(key, (ignoredKey, previous) -> {
            long lastSuccessSequence = pointState(point).lastSuccessSequence();
            boolean wasRecovered = (previous == null) || (previous.failureSequence() <= lastSuccessSequence);
            episodeOpened[0] = wasRecovered;
            Instant firstSeenAt = (previous == null) ? observedAt : previous.firstSeenAt();
            long failureSequence = (previous == null) ? sequence : Math.max(sequence, previous.failureSequence());

            return new DiagnosticEpisode(failureSequence, firstSeenAt, observedAt);
        });

        if (episodeOpened[0]) {
            warnOncePerEpisodeRateLimited(key, observedAt);
        }
    }

    private void warnOncePerEpisodeRateLimited(JmxDiagnosticKey key, Instant observedAt) {
        AtomicLong lastWarn = lastWarnEpochMillis.computeIfAbsent(key, ignored -> new AtomicLong(Long.MIN_VALUE));
        long now = observedAt.toEpochMilli();
        long previousWarn = lastWarn.get();
        if (previousWarn != Long.MIN_VALUE && (now - previousWarn) < warnRateLimitMillis) {
            suppressedWarnCounts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
            return;
        }

        if (lastWarn.compareAndSet(previousWarn, now)) {
            log.warn("JMX contract: {} {} at point {} (first occurrence or repeat after recovery)",
                    key.category(), key.kind(), key.point());
        } else {
            suppressedWarnCounts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
    }

    private record DiagnosticEpisode(long failureSequence, Instant firstSeenAt, Instant lastSeenAt) {
    }
}
