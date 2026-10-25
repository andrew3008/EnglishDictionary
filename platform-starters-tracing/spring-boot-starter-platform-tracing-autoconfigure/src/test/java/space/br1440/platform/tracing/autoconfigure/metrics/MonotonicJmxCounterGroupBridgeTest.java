package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

class MonotonicJmxCounterGroupBridgeTest {

    private enum Key { A, B }

    private static final String AGENT_INSTANCE_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String NEXT_AGENT_INSTANCE_ID = "123e4567-e89b-12d3-a456-426614174001";
    private static final long WINDOW_NANOS = 100_000_000L;

    @Test
    void firstObservationPopulatesAccumulatedWithoutResetEvent() {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 5L, Key.B, 2L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);

        assertThat(bridge.count(Key.A)).isEqualTo(5.0);
        assertThat(bridge.count(Key.B)).isEqualTo(2.0);
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.OWNER_REPLACED)).isZero();
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.AGENT_INSTANCE_CHANGED)).isZero();
    }

    @Test
    void sameGenerationAccumulatesPositiveDeltaPerKey() {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 5L, Key.B, 2L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);

        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 8L, Key.B, 2L), 1);

        assertThat(bridge.count(Key.A)).isEqualTo(8.0);
        assertThat(bridge.count(Key.B)).isEqualTo(2.0);
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.OWNER_REPLACED)).isZero();
    }

    @Test
    void ownerReplacementAddsFirstRawAsIncrementWithoutDecreasingAccumulated() {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 100L, Key.B, 40L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);

        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 3L, Key.B, 1L), 2);

        assertThat(bridge.count(Key.A)).isEqualTo(103.0);
        assertThat(bridge.count(Key.B)).isEqualTo(41.0);
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.OWNER_REPLACED)).isEqualTo(1);
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.AGENT_INSTANCE_CHANGED)).isZero();
        assertThat(source.reads.get()).isEqualTo(2L);
    }

    @Test
    void ownerReplacementSaturatesAccumulatedCounterAtLongMaximum() {
        // given
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, Long.MAX_VALUE), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);
        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 1L), 2);

        // when
        double observed = bridge.count(Key.A);

        // then
        assertThat(observed).isEqualTo((double) Long.MAX_VALUE);
        assertThat(observed).isPositive();
    }

    @Test
    void positiveDeltaSaturatesAccumulatedCounterAtLongMaximum() {
        // given
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, Long.MAX_VALUE - 2L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);
        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 1L), 2);
        bridge.count(Key.A);
        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 3L), 2);

        // when
        double observed = bridge.count(Key.A);

        // then
        assertThat(observed).isEqualTo((double) Long.MAX_VALUE);
        assertThat(observed).isPositive();
    }

    @Test
    void agentInstanceChangeIsClassifiedSeparatelyFromOwnerReplacementAndDoesNotDecreaseAccumulated() {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 100L, Key.B, 40L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);

        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 3L, Key.B, 1L), 1, NEXT_AGENT_INSTANCE_ID);

        assertThat(bridge.count(Key.A)).isEqualTo(103.0);
        assertThat(bridge.count(Key.B)).isEqualTo(41.0);
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.AGENT_INSTANCE_CHANGED)).isEqualTo(1);
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.OWNER_REPLACED)).isZero();
        assertThat(source.reads.get()).isEqualTo(2L);
    }

    @Test
    void backwardAnomalyIsolatedToAffectedKeyOthersStillAdvance() {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 10L, Key.B, 10L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);

        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 4L, Key.B, 15L), 1);

        assertThat(bridge.count(Key.A)).isEqualTo(10.0);
        assertThat(bridge.count(Key.B)).isEqualTo(15.0);
        assertThat(bridge.anomalyCount(Key.A)).isEqualTo(1);
        assertThat(bridge.anomalyCount(Key.B)).isZero();
        assertThat(bridge.resetCount(MonotonicJmxCounterGroupBridge.ResetCause.OWNER_REPLACED)).isZero();
        assertThat(source.reads.get()).isEqualTo(2L);
    }

    @Test
    void unavailableReadDoesNotChangeAccumulatedButRefreshesTimestamp() {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 7L, Key.B, 1L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);

        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publishUnavailable();

        assertThat(bridge.count(Key.A)).isEqualTo(7.0);
        assertThat(bridge.count(Key.B)).isEqualTo(1.0);
        assertThat(source.reads.get()).isEqualTo(2L);
    }

    @Test
    void coherenceFailureKeepsLastAccumulatedSnapshot() {
        // given
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 7L, Key.B, 1L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);
        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.failWith(mock(AgentReadinessCoherenceException.class));

        // when
        double first = bridge.count(Key.A);
        double second = bridge.count(Key.B);

        // then
        assertThat(first).isEqualTo(7.0);
        assertThat(second).isEqualTo(1.0);
    }

    @Test
    void coalescingWindowSuppressesRefreshUntilElapsed() {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 1L, Key.B, 1L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);

        source.publish(Map.of(Key.A, 999L, Key.B, 999L), 1);
        nanoTime.addAndGet(WINDOW_NANOS - 1);

        assertThat(bridge.count(Key.A)).isEqualTo(1.0);
        assertThat(source.reads.get()).isEqualTo(1);
    }

    @Test
    void concurrentCountersNeverBlockOnTryLockLoser() throws Exception {
        AtomicLong nanoTime = new AtomicLong(0);
        FakeSource source = new FakeSource();
        source.publish(Map.of(Key.A, 1L, Key.B, 1L), 1);
        MonotonicJmxCounterGroupBridge<Key> bridge = newBridge(source, nanoTime);
        bridge.count(Key.A);

        nanoTime.addAndGet(WINDOW_NANOS + 1);
        source.publish(Map.of(Key.A, 2L, Key.B, 2L), 1);

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    awaitQuietly(go);
                    bridge.count(Key.A);
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(source.reads.get()).isEqualTo(2);
        assertThat(bridge.count(Key.A)).isEqualTo(2.0);
    }

    private static MonotonicJmxCounterGroupBridge<Key> newBridge(FakeSource source, AtomicLong nanoTime) {
        return new MonotonicJmxCounterGroupBridge<>(Key.class, source, nanoTime::get, WINDOW_NANOS);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was not released before the deadline");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the latch", e);
        }
    }

    /** Управляемый источник, считающий число фактических JMX-чтений для проверки coalescing. */
    private static final class FakeSource implements
            Supplier<Optional<MonotonicJmxCounterGroupBridge.SourceSnapshot<Key>>> {

        private final AtomicLong reads = new AtomicLong();
        private volatile MonotonicJmxCounterGroupBridge.SourceSnapshot<Key> current;
        private volatile AgentReadinessCoherenceException failure;

        void publish(Map<Key, Long> values, long generation) {
            publish(values, generation, AGENT_INSTANCE_ID);
        }

        void publish(Map<Key, Long> values, long generation, String agentInstanceId) {
            EnumMap<Key, Long> copy = new EnumMap<>(values);
            current = new MonotonicJmxCounterGroupBridge.SourceSnapshot<>(
                    new JmxOwnerState.Present<>(copy, generation),
                    agentInstanceId
            );
        }

        void publishUnavailable() {
            current = null;
        }

        void failWith(AgentReadinessCoherenceException coherenceFailure) {
            failure = coherenceFailure;
        }

        @Override
        public Optional<MonotonicJmxCounterGroupBridge.SourceSnapshot<Key>> get() {
            reads.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(current);
        }
    }
}
