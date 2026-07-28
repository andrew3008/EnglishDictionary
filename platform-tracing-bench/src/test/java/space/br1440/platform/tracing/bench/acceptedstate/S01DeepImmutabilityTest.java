package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S-01 corrective evidence for blocker B3: the accepted policy payload is deeply immutable, so a
 * published envelope cannot change after publication and no caller-owned mutable state is aliased.
 * <p>
 * The original spike proved the commit mechanism over a generic payload {@code S}. That proof says
 * nothing about the payload itself: an arbitrary {@code S} may hold a mutable map and still satisfy
 * the store's compile-time contract. These tests close that gap against the concrete
 * {@link S01EffectiveTracingPolicy}.
 */
@DisplayName("S-01 corrective: accepted policy is deeply immutable after publication")
class S01DeepImmutabilityTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";

    @Test
    @DisplayName("mutating the source map after publication cannot change the accepted policy")
    void sourceMapMutationDoesNotReachAcceptedPolicy() {
        // given: a mutable caller-owned map, as a real ingress path would supply
        Map<String, Double> callerOwned = new HashMap<>();
        callerOwned.put("/api/orders", 0.25d);
        callerOwned.put("/api/users", 0.50d);

        S01EffectiveTracingPolicy policy = S01EffectiveTracingPolicy.of(
                true, 0.10d, callerOwned, true, false, true, true, S01LogLevel.INFO);
        S01AcceptedPolicyStateStore store = new S01AcceptedPolicyStateStore(RUNTIME_INSTANCE_ID, policy);
        S01AcceptedPolicyEnvelope published = store.current();

        // when: the caller mutates its own map in every way that could alias through
        callerOwned.put("/api/orders", 1.0d);
        callerOwned.put("/api/injected", 0.99d);
        callerOwned.remove("/api/users");
        callerOwned.clear();

        // then
        NavigableMap<String, Double> accepted = published.policy().samplingRouteRatios();
        assertThat(accepted)
                .as("the constructor copied on ingress, so the accepted policy owns its map")
                .containsExactly(
                        Map.entry("/api/orders", 0.25d),
                        Map.entry("/api/users", 0.50d));
        assertThat(store.current())
                .as("no commit occurred, so the published envelope is still the same instance")
                .isSameAs(published);
    }

    @Test
    @DisplayName("the canonical constructor also copies, so no construction path aliases the source")
    void canonicalConstructorAlsoCopiesOnIngress() {
        // given
        NavigableMap<String, Double> callerOwned = new TreeMap<>();
        callerOwned.put("/a", 0.5d);

        S01EffectiveTracingPolicy policy = new S01EffectiveTracingPolicy(
                true, 0.2d, callerOwned, false, false, false, false, S01LogLevel.WARN);

        // when
        callerOwned.put("/b", 0.7d);

        // then
        assertThat(policy.samplingRouteRatios()).containsExactly(Map.entry("/a", 0.5d));
        assertThat(policy.samplingRouteRatios())
                .as("the accessor must not return the caller's backing map")
                .isNotSameAs(callerOwned);
    }

    @Test
    @DisplayName("every mutating view of the exposed route map throws, including sub-maps and iterators")
    void exposedRouteMapIsUnmodifiableThroughEveryView() {
        // given
        S01EffectiveTracingPolicy policy = S01EffectiveTracingPolicy.of(
                true, 0.5d, Map.of("/a", 0.1d, "/b", 0.2d),
                true, true, true, true, S01LogLevel.DEBUG);
        NavigableMap<String, Double> routes = policy.samplingRouteRatios();

        // then
        assertThatThrownBy(() -> routes.put("/c", 0.3d))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.remove("/a"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(routes::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.putAll(Map.of("/d", 0.4d)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.keySet().remove("/a"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.values().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.entrySet().iterator().remove())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.descendingMap().put("/e", 0.5d))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.headMap("/b", true).put("/f", 0.6d))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> routes.pollFirstEntry())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a committed candidate is equally deeply immutable, so commit cannot smuggle a mutable map")
    void committedCandidateIsDeeplyImmutable() {
        // given
        S01AcceptedPolicyStateStore store = new S01AcceptedPolicyStateStore(
                RUNTIME_INSTANCE_ID, policy(0.1d, Map.of("/base", 0.1d)));
        Map<String, Double> callerOwned = new HashMap<>();
        callerOwned.put("/candidate", 0.75d);

        // when
        S01ConcreteCommitResult result = store.commit(
                store.current().expectedBase(),
                S01EffectiveTracingPolicy.of(true, 0.9d, callerOwned,
                        true, true, true, true, S01LogLevel.TRACE));
        callerOwned.put("/candidate", 0.0d);
        callerOwned.put("/late", 0.5d);

        // then
        assertThat(result.outcome()).isEqualTo(S01CommitOutcome.APPLIED);
        assertThat(store.current().policy().samplingRouteRatios())
                .containsExactly(Map.entry("/candidate", 0.75d));
        assertThatThrownBy(() -> store.current().policy().samplingRouteRatios().put("/x", 0.1d))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("concurrent readers never observe the published object graph changing")
    void concurrentReadersObserveAStableObjectGraph() throws InterruptedException {
        // given
        int readerCount = 6;
        long readsPerReader = 200_000L;
        Map<String, Double> callerOwned = new HashMap<>();
        callerOwned.put("/stable", 0.42d);

        S01AcceptedPolicyStateStore store = new S01AcceptedPolicyStateStore(
                RUNTIME_INSTANCE_ID,
                S01EffectiveTracingPolicy.of(true, 0.42d, callerOwned,
                        true, false, true, true, S01LogLevel.INFO));
        S01AcceptedPolicyEnvelope published = store.current();

        AtomicLong violations = new AtomicLong();
        AtomicLong observations = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);

        try {
            for (int reader = 0; reader < readerCount; reader++) {
                pool.submit(() -> {
                    start.await();
                    for (long i = 0; i < readsPerReader; i++) {
                        S01AcceptedPolicyEnvelope observed = store.current();
                        NavigableMap<String, Double> routes = observed.policy().samplingRouteRatios();
                        boolean intact = observed == published
                                && routes.size() == 1
                                && Double.compare(routes.get("/stable"), 0.42d) == 0
                                && Double.compare(observed.policy().samplingRatio(), 0.42d) == 0
                                && observed.policy().diagnosticsLogLevel() == S01LogLevel.INFO
                                && observed.acceptedSequence() == 0L;
                        if (!intact) {
                            violations.incrementAndGet();
                        }
                        observations.incrementAndGet();
                    }
                    return null;
                });
            }

            // a concurrent mutator hammering the source map it no longer owns
            pool.submit(() -> {
                start.await();
                for (long i = 0; i < readsPerReader; i++) {
                    callerOwned.put("/stable", (double) (i % 100) / 100.0d);
                    callerOwned.put("/noise-" + (i % 8), 0.5d);
                    callerOwned.clear();
                }
                return null;
            });

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120L, TimeUnit.SECONDS))
                    .as("readers and the source mutator must finish")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        // then
        assertThat(observations.get()).isEqualTo(readerCount * readsPerReader);
        assertThat(violations.get())
                .as("%d reads across %d threads observed no change to the published graph",
                        observations.get(), readerCount)
                .isZero();
    }

    @Test
    @DisplayName("a route map with a null value or an out-of-range ratio is rejected at ingress")
    void invalidRouteEntriesAreRejectedAtIngress() {
        // given
        Map<String, Double> withNullValue = new HashMap<>();
        withNullValue.put("/a", null);

        // then
        assertThatThrownBy(() -> policy(0.5d, withNullValue))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy(0.5d, Map.of("/a", 1.5d)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(-0.1d, Map.of("/a", 0.5d)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static S01EffectiveTracingPolicy policy(double ratio, Map<String, Double> routes) {
        return S01EffectiveTracingPolicy.of(true, ratio, routes,
                true, false, true, true, S01LogLevel.INFO);
    }
}
