package space.br1440.platform.tracing.autoconfigure.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

/**
 * Мост между одним JMX-владельцем, отдающим несколько cumulative-значений, и Micrometer {@code FunctionCounter}.
 * Один observation обрабатывается целиком: отдельный bridge на каждый meter означал бы N JMX-захватов одного владельца за один
 * scrape и N-кратное обнаружение одной и той же смены владельца.
 * <p>
 * Coalescing-окно на injected {@link Ticker} объединяет вызовы одного scrape: Micrometer не
 * даёт универсального {@code scrapeId}, поэтому без окна каждый {@code FunctionCounter} одного
 * владельца инициировал бы отдельный JMX-capture.
 */
final class MonotonicJmxCounterGroupBridge<K extends Enum<K>> {

    static final long DEFAULT_COALESCING_WINDOW_NANOS = 150_000_000L;
    private static final long NEVER_OBSERVED_GENERATION = -1L;
    private static final long NEVER_REFRESHED_AT_NANOS = Long.MIN_VALUE;

    static final String RESET_METRIC_NAME = "platform.tracing.jmx.source_resets_total";
    static final String ANOMALY_METRIC_NAME = "platform.tracing.jmx.backward_value_anomalies_total";

    enum ResetCause {
        OWNER_REPLACED("owner_replaced"),
        AGENT_INSTANCE_CHANGED("agent_instance_changed");

        private final String tag;

        ResetCause(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    @FunctionalInterface
    interface Ticker {
        long nanoTime();
    }

    record SourceSnapshot<K extends Enum<K>>(JmxOwnerState<Map<K, Long>> ownerState,
                                              String agentInstanceId) {

        SourceSnapshot {
            Objects.requireNonNull(ownerState, "ownerState");
            Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        }
    }

    private final Class<K> keyType;
    private final Supplier<Optional<SourceSnapshot<K>>> source;
    private final Ticker ticker;
    private final long coalescingWindowNanos;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Map<K, LongAdder> anomalyCounts;
    private final Map<ResetCause, LongAdder> resetCounts;

    private volatile BridgeSnapshot<K> snapshot;

    MonotonicJmxCounterGroupBridge(Class<K> keyType,
                                   Supplier<Optional<SourceSnapshot<K>>> source,
                                   Ticker ticker,
                                   long coalescingWindowNanos) {
        this.keyType = Objects.requireNonNull(keyType, "keyType");
        this.source = Objects.requireNonNull(source, "source");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.coalescingWindowNanos = coalescingWindowNanos;

        this.anomalyCounts = new EnumMap<>(keyType);
        for (K key : keyType.getEnumConstants()) {
            anomalyCounts.put(key, new LongAdder());
        }

        this.resetCounts = new EnumMap<>(ResetCause.class);
        for (ResetCause cause : ResetCause.values()) {
            resetCounts.put(cause, new LongAdder());
        }

        this.snapshot = BridgeSnapshot.initial(keyType);
    }

    double count(K key) {
        return refreshed().accumulated().getOrDefault(key, 0L).doubleValue();
    }

    long resetCount(ResetCause cause) {
        return resetCounts.get(cause).sum();
    }

    long anomalyCount(K key) {
        return anomalyCounts.get(key).sum();
    }

    private BridgeSnapshot<K> refreshed() {
        BridgeSnapshot<K> current = snapshot;
        if (isFresh(current)) {
            return current;
        }

        if (refreshLock.tryLock()) {
            try {
                current = snapshot;
                if (!isFresh(current)) {
                    current = doRefresh(current);
                    snapshot = current;
                }

                return current;
            } finally {
                refreshLock.unlock();
            }
        }

        return snapshot;
    }

    private boolean isFresh(BridgeSnapshot<K> current) {
        return (current.refreshedAtNanos() != NEVER_REFRESHED_AT_NANOS)
                && ((ticker.nanoTime() - current.refreshedAtNanos()) < coalescingWindowNanos);
    }

    private BridgeSnapshot<K> doRefresh(BridgeSnapshot<K> previous) {
        long now = ticker.nanoTime();

        Optional<SourceSnapshot<K>> observed;
        try {
            observed = source.get();
        } catch (AgentReadinessCoherenceException ignored) {
            return previous.withRefreshedAt(now);
        }

        if (observed.isEmpty() ||
                !(observed.get().ownerState() instanceof JmxOwnerState.Present<Map<K, Long>>(
                        Map<K, Long> values, long generation
                ))) {
            return previous.withRefreshedAt(now);
        }

        String currentAgentInstanceId = observed.get().agentInstanceId();

        boolean neverObserved = previous.lastGeneration() == NEVER_OBSERVED_GENERATION;
        boolean agentInstanceChanged = !neverObserved && !currentAgentInstanceId.equals(previous.lastAgentInstanceId());
        boolean ownerReplaced = !neverObserved && !agentInstanceChanged && previous.lastGeneration() != generation;
        if (agentInstanceChanged) {
            resetCounts.get(ResetCause.AGENT_INSTANCE_CHANGED).increment();
        } else if (ownerReplaced) {
            resetCounts.get(ResetCause.OWNER_REPLACED).increment();
        }

        boolean needIdentityReset = neverObserved || agentInstanceChanged || ownerReplaced;

        EnumMap<K, Long> accumulated = new EnumMap<>(previous.accumulated());
        EnumMap<K, Long> lastRaw = new EnumMap<>(keyType);
        for (K key : keyType.getEnumConstants()) {
            long currentRaw = values.getOrDefault(key, 0L);
            lastRaw.put(key, currentRaw);
            if (needIdentityReset) {
                accumulateSaturated(accumulated, key, currentRaw);
                continue;
            }

            long previousRaw = previous.lastRaw().getOrDefault(key, 0L);
            long delta = currentRaw - previousRaw;
            if (delta < 0) {
                anomalyCounts.get(key).increment();
            } else if (delta > 0) {
                accumulateSaturated(accumulated, key, delta);
            }
        }

        return new BridgeSnapshot<>(accumulated, lastRaw, currentAgentInstanceId, generation, now);
    }

    private static <K extends Enum<K>> void accumulateSaturated(EnumMap<K, Long> accumulated,
                                                                 K key,
                                                                 long increment) {
        accumulated.compute(key, (ignored, current) -> saturatingAdd(current == null ? 0L : current, increment));
    }

    private static long saturatingAdd(long current, long increment) {
        if (increment <= 0L) {
            return current;
        }
        if (current >= Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return current + increment;
    }

    private record BridgeSnapshot<K extends Enum<K>>(EnumMap<K, Long> accumulated,
                                                     EnumMap<K, Long> lastRaw,
                                                     String lastAgentInstanceId,
                                                     long lastGeneration,
                                                     long refreshedAtNanos) {

        static <K extends Enum<K>> BridgeSnapshot<K> initial(Class<K> keyType) {
            return new BridgeSnapshot<>(
                    new EnumMap<>(keyType),
                    new EnumMap<>(keyType),
                    null,
                    NEVER_OBSERVED_GENERATION,
                    NEVER_REFRESHED_AT_NANOS
            );
        }

        BridgeSnapshot<K> withRefreshedAt(long now) {
            return new BridgeSnapshot<>(accumulated, lastRaw, lastAgentInstanceId, lastGeneration, now);
        }
    }
}
