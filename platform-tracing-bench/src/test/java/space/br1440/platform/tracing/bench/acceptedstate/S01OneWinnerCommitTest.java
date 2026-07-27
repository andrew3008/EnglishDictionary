package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate E3 one-winner proof for {@link S01AcceptedPolicyStore}.
 * <p>
 * Every round races {@value #WRITERS} writers that all present the <b>same</b> expected base. The
 * contract under test is that exactly one of them is told {@code APPLIED} and the rest are told
 * {@code STALE}, that the accepted sequence advances by exactly one, and that the surviving policy is
 * the winner's candidate.
 * <p>
 * The sequence assertion is what makes this a rebase proof rather than just a mutual-exclusion proof:
 * if the store retried internally on behalf of the losers, the sequence would advance by
 * {@value #WRITERS} per round instead of one, and every writer would report success.
 */
@DisplayName("S-01: expected-base commit yields exactly one winner and no rebase")
class S01OneWinnerCommitTest {

    private static final String RUNTIME_INSTANCE_ID = "s01-runtime";
    private static final int WRITERS = 8;
    private static final int ROUNDS = 250;

    @Test
    @DisplayName("N writers on one expected base → 1 APPLIED, N-1 STALE, sequence +1, winner survives")
    void singleWinnerPerExpectedBase() throws Exception {
        // given
        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(0L));
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        AtomicInteger candidateBuilds = new AtomicInteger();

        try {
            for (int round = 1; round <= ROUNDS; round++) {
                S01AcceptedEnvelope<S01Policy> before = store.current();
                S01ExpectedBase sharedBase = before.expectedBase();
                CyclicBarrier startTogether = new CyclicBarrier(WRITERS);

                List<Callable<S01CommitResult<S01Policy>>> attempts = new ArrayList<>(WRITERS);
                for (int writer = 0; writer < WRITERS; writer++) {
                    long candidateSeed = (long) round * WRITERS + writer;
                    attempts.add(() -> {
                        // Candidate is built exactly once, by the caller, before the store is reached.
                        S01Policy candidate = S01Policy.of(candidateSeed);
                        candidateBuilds.incrementAndGet();
                        startTogether.await(10, TimeUnit.SECONDS);
                        return store.commit(sharedBase, candidate);
                    });
                }

                // when
                List<Future<S01CommitResult<S01Policy>>> futures = pool.invokeAll(attempts);

                // then
                List<S01CommitResult<S01Policy>> results = new ArrayList<>(WRITERS);
                for (Future<S01CommitResult<S01Policy>> future : futures) {
                    results.add(future.get(10, TimeUnit.SECONDS));
                }

                List<S01CommitResult<S01Policy>> winners = results.stream()
                        .filter(S01CommitResult::applied)
                        .toList();

                assertThat(winners)
                        .as("round %d: exactly one writer may win one expected base", round)
                        .hasSize(1);
                assertThat(results)
                        .filteredOn(result -> result.outcome() == S01CommitOutcome.STALE)
                        .as("round %d: every loser is told STALE", round)
                        .hasSize(WRITERS - 1);

                S01AcceptedEnvelope<S01Policy> after = store.current();
                assertThat(after.acceptedSequence())
                        .as("round %d: sequence advances by exactly one, so no loser was retried", round)
                        .isEqualTo(before.acceptedSequence() + 1L);
                assertThat(after.policy())
                        .as("round %d: the winner's candidate survives, with no lost update", round)
                        .isEqualTo(winners.get(0).accepted().policy());
                assertThat(after.runtimeInstanceId())
                        .as("round %d: runtime identity is carried forward, never reassigned", round)
                        .isEqualTo(RUNTIME_INSTANCE_ID);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(store.current().acceptedSequence())
                .as("exactly one accepted commit per round")
                .isEqualTo(ROUNDS);
        assertThat(candidateBuilds.get())
                .as("each writer built its candidate exactly once; the store never rebuilt any candidate")
                .isEqualTo(ROUNDS * WRITERS);
    }

    @Test
    @DisplayName("losing attempt returns the fresh base, so the caller can decide to recompute")
    void staleResultCarriesFreshBase() {
        // given
        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(0L));
        S01ExpectedBase staleBase = store.current().expectedBase();
        store.commit(staleBase, S01Policy.of(1L));

        // when
        S01CommitResult<S01Policy> loser = store.commit(staleBase, S01Policy.of(2L));

        // then
        assertThat(loser.outcome()).isEqualTo(S01CommitOutcome.STALE);
        assertThat(loser.accepted().acceptedSequence()).isEqualTo(1L);
        assertThat(loser.accepted().policy()).isEqualTo(S01Policy.of(1L));
        assertThat(store.current().policy())
                .as("the losing candidate is never published")
                .isEqualTo(S01Policy.of(1L));
    }
}
