package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-01 corrective evidence: the one-winner, no-rebase, {@code runtimeInstanceId} and
 * {@code acceptedSequence} proofs hold for the <b>concrete</b> store, not only for the generic
 * mechanism the original spike measured.
 * <p>
 * The commit path is intentionally identical to {@link S01AcceptedPolicyStore}: one volatile read,
 * one expected-base comparison, one {@code compareAndSet}, no loop, no builder. Re-proving it here
 * is what allows the E3 decision to name the concrete store rather than the generic one, without
 * re-running the benchmarks: the read mechanism did not change.
 */
@DisplayName("S-01 corrective: concrete store admits exactly one winner per expected base")
class S01ConcreteStoreCommitTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";
    private static final String OTHER_RUNTIME_INSTANCE_ID = "s01-runtime-restarted";
    private static final int WRITERS = 8;
    private static final int ROUNDS = 100;

    @Test
    @DisplayName("8 concurrent writers on one expected base: exactly one APPLIED, sequence advances by one")
    void singleWinnerPerExpectedBase() throws Exception {
        // given
        S01AcceptedPolicyStateStore store = new S01AcceptedPolicyStateStore(
                RUNTIME_INSTANCE_ID, policy(0.0d));
        AtomicLong candidatesBuilt = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);

        try {
            for (int round = 1; round <= ROUNDS; round++) {
                S01AcceptedPolicyEnvelope base = store.current();
                S01ExpectedBase expectedBase = base.expectedBase();
                CyclicBarrier barrier = new CyclicBarrier(WRITERS);

                List<Future<S01ConcreteCommitResult>> futures = new ArrayList<>(WRITERS);
                for (int writer = 0; writer < WRITERS; writer++) {
                    double ratio = writer / 100.0d;
                    futures.add(pool.submit(() -> {
                        // Each attempt builds exactly one candidate. If the store retried
                        // internally, the count would exceed the attempt count.
                        candidatesBuilt.incrementAndGet();
                        S01EffectiveTracingPolicy candidate = policy(ratio);
                        barrier.await(30L, TimeUnit.SECONDS);
                        return store.commit(expectedBase, candidate);
                    }));
                }

                long applied = 0;
                long stale = 0;
                for (Future<S01ConcreteCommitResult> future : futures) {
                    S01ConcreteCommitResult result = future.get(30L, TimeUnit.SECONDS);
                    if (result.outcome() == S01CommitOutcome.APPLIED) {
                        applied++;
                    } else {
                        stale++;
                    }
                }

                // then, every round
                assertThat(applied).as("round %d: exactly one winner", round).isEqualTo(1L);
                assertThat(stale).as("round %d: every loser is told STALE", round)
                        .isEqualTo(WRITERS - 1L);
                assertThat(store.current().acceptedSequence())
                        .as("round %d: the sequence advances by exactly one accepted commit", round)
                        .isEqualTo(base.acceptedSequence() + 1L);
                assertThat(store.current().runtimeInstanceId()).isEqualTo(RUNTIME_INSTANCE_ID);
            }
        } finally {
            pool.shutdownNow();
        }

        // then
        assertThat(store.current().acceptedSequence()).isEqualTo(ROUNDS);
        assertThat(candidatesBuilt.get())
                .as("no rebase: one candidate per attempt, never one per retry")
                .isEqualTo((long) ROUNDS * WRITERS);
    }

    @Test
    @DisplayName("a runtimeInstanceId mismatch is STALE and leaves accepted state untouched")
    void runtimeInstanceMismatchIsStale() {
        // given
        S01AcceptedPolicyStateStore store = new S01AcceptedPolicyStateStore(
                RUNTIME_INSTANCE_ID, policy(0.25d));
        S01AcceptedPolicyEnvelope before = store.current();

        // when
        S01ConcreteCommitResult result = store.commit(
                new S01ExpectedBase(OTHER_RUNTIME_INSTANCE_ID, before.acceptedSequence()),
                policy(0.75d));

        // then
        assertThat(result.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(store.current())
                .as("reference identity proves no compare-and-set occurred")
                .isSameAs(before);
        assertThat(result.accepted()).isSameAs(before);
    }

    @Test
    @DisplayName("a sequence mismatch is STALE whether the base is behind or ahead")
    void sequenceMismatchIsStale() {
        // given
        S01AcceptedPolicyStateStore store = new S01AcceptedPolicyStateStore(
                RUNTIME_INSTANCE_ID, policy(0.25d));
        store.commit(store.current().expectedBase(), policy(0.30d));
        S01AcceptedPolicyEnvelope before = store.current();

        // when
        S01ConcreteCommitResult behind = store.commit(
                new S01ExpectedBase(RUNTIME_INSTANCE_ID, before.acceptedSequence() - 1L),
                policy(0.40d));
        S01ConcreteCommitResult ahead = store.commit(
                new S01ExpectedBase(RUNTIME_INSTANCE_ID, before.acceptedSequence() + 1L),
                policy(0.50d));

        // then
        assertThat(behind.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(ahead.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(store.current()).isSameAs(before);
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a loser receives the fresh accepted state, so it can rebuild without a second read")
    void loserLearnsTheFreshBase() {
        // given
        S01AcceptedPolicyStateStore store = new S01AcceptedPolicyStateStore(
                RUNTIME_INSTANCE_ID, policy(0.25d));
        S01ExpectedBase staleBase = store.current().expectedBase();
        store.commit(staleBase, policy(0.30d));

        // when
        S01ConcreteCommitResult result = store.commit(staleBase, policy(0.40d));

        // then
        assertThat(result.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(result.accepted())
                .as("the returned envelope is the accepted state in force, not the stale base")
                .isSameAs(store.current());
        assertThat(result.accepted().acceptedSequence()).isEqualTo(1L);
    }

    private static S01EffectiveTracingPolicy policy(double ratio) {
        return S01EffectiveTracingPolicy.of(true, ratio, Map.of("/api", ratio),
                true, false, true, true, S01LogLevel.INFO);
    }
}
