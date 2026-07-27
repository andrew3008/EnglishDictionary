package space.br1440.platform.tracing.bench.acceptedstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.runtime.versioned.VersionedState;
import space.br1440.platform.tracing.otel.runtime.versioned.VersionedStateHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable falsification of Candidate G0 for Gate E3: the existing
 * {@link VersionedStateHolder} cannot express expected-base commit semantics.
 * <p>
 * These tests characterise the current primitive rather than accuse it of a defect. Its documented
 * contract is last-known-good with an internal CAS-retry loop, and
 * {@code VersionedStateHolderTest.concurrentUpdatesAreSerializedWithoutLoss} already asserts that all
 * concurrent updates succeed. That contract is correct for the domain it serves and is simultaneously
 * the exact opposite of the E3 requirement that one expected base admits one winner and that every
 * loser is told {@code STALE}.
 * <p>
 * What is proven here:
 * <ul>
 *   <li>the primitive has no expected-base parameter, so runtime identity and accepted sequence cannot
 *       be presented at all;</li>
 *   <li>a caller holding a stale base is never told {@code STALE} — it is silently retried;</li>
 *   <li>the retry rebases the candidate onto the winner's state, and the stale caller then overwrites
 *       the winner;</li>
 *   <li>the accepted sequence therefore advances once per attempt rather than once per accepted
 *       commit.</li>
 * </ul>
 */
@DisplayName("S-01: VersionedStateHolder cannot express expected-base commit (Candidate G0)")
class S01VersionedStateHolderContrastTest {

    private static final int WRITERS = 8;

    private record Snapshot(long version, String label) implements VersionedState {
    }

    @Test
    @DisplayName("surface: no expected base, and boolean cannot distinguish APPLIED from STALE")
    void surfaceCannotExpressExpectedBase() {
        // when
        var updateMethods = Arrays.stream(VersionedStateHolder.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("tryUpdate"))
                .toList();

        // then
        assertThat(updateMethods).isNotEmpty();
        assertThat(updateMethods)
                .as("every mutation returns boolean, so APPLIED and STALE are indistinguishable")
                .allSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(boolean.class));
        assertThat(updateMethods)
                .as("no mutation accepts an expected base, so runtimeInstanceId and acceptedSequence "
                        + "cannot participate in the commit decision")
                .allSatisfy(method -> assertThat(Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> type.getSimpleName().toLowerCase().contains("expected")))
                        .isFalse());
    }

    @Test
    @DisplayName("stale caller is silently retried, rebased onto the winner, and overwrites it")
    void staleCallerIsRebasedAndOverwritesWinner() throws Exception {
        // given
        VersionedStateHolder<Snapshot> holder = new VersionedStateHolder<>(new Snapshot(0L, "initial"));
        CountDownLatch builderEntered = new CountDownLatch(1);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        List<Long> observedBases = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger builderInvocations = new AtomicInteger();
        AtomicBoolean staleCallerApplied = new AtomicBoolean();

        Thread staleCaller = new Thread(() -> {
            boolean applied = holder.tryUpdate(previous -> {
                int invocation = builderInvocations.incrementAndGet();
                observedBases.add(previous.version());
                if (invocation == 1) {
                    // Park inside the builder so the winner commits before this caller's CAS runs.
                    builderEntered.countDown();
                    try {
                        winnerCommitted.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new Snapshot(previous.version() + 1L, "stale-caller");
            });
            staleCallerApplied.set(applied);
        }, "s01-stale-caller");
        staleCaller.setDaemon(true);
        staleCaller.start();

        assertThat(builderEntered.await(30, TimeUnit.SECONDS)).isTrue();

        // when: a concurrent winner commits while the stale caller is parked on version 0
        boolean winnerApplied = holder.tryUpdate(previous -> new Snapshot(previous.version() + 1L, "winner"));
        winnerCommitted.countDown();
        staleCaller.join(TimeUnit.SECONDS.toMillis(30));

        // then
        assertThat(winnerApplied).isTrue();
        assertThat(staleCaller.isAlive()).isFalse();
        assertThat(builderInvocations.get())
                .as("the builder ran twice: the store retried internally")
                .isEqualTo(2);
        assertThat(observedBases)
                .as("the second run was handed the winner's state: this is a rebase")
                .containsExactly(0L, 1L);
        assertThat(staleCallerApplied.get())
                .as("the stale caller was never told STALE; it was told it succeeded")
                .isTrue();
        assertThat(holder.current().label())
                .as("the stale caller overwrote the winner, which E3 forbids")
                .isEqualTo("stale-caller");
        assertThat(holder.current().version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("N writers on one base → N winners and sequence +N, where E3 requires 1 and +1")
    void everyWriterWinsAndSequenceAdvancesPerAttempt() throws Exception {
        // given
        VersionedStateHolder<Snapshot> holder = new VersionedStateHolder<>(new Snapshot(0L, "initial"));
        long baseVersion = holder.version();
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CyclicBarrier startTogether = new CyclicBarrier(WRITERS);

        List<Callable<Boolean>> attempts = new ArrayList<>(WRITERS);
        for (int writer = 0; writer < WRITERS; writer++) {
            String label = "writer-" + writer;
            attempts.add(() -> {
                startTogether.await(30, TimeUnit.SECONDS);
                // Every writer intends to build on baseVersion, but the primitive has no way to say so.
                return holder.tryUpdate(previous -> new Snapshot(previous.version() + 1L, label));
            });
        }

        try {
            // when
            List<Future<Boolean>> futures = pool.invokeAll(attempts);
            List<Boolean> outcomes = new ArrayList<>(WRITERS);
            for (Future<Boolean> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            // then
            assertThat(outcomes)
                    .as("all %d writers report success; not one is told STALE", WRITERS)
                    .containsOnly(Boolean.TRUE);
            assertThat(holder.version())
                    .as("sequence advanced once per attempt (+%d); E3 requires once per accepted "
                            + "commit (+1)", WRITERS)
                    .isEqualTo(baseVersion + WRITERS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("contrast: the S-01 store admits one winner for the same race")
    void s01StoreAdmitsOneWinnerForTheSameRace() throws Exception {
        // given
        S01AcceptedPolicyStore<S01Policy> store =
                new S01AcceptedPolicyStore<>("s01-runtime", S01Policy.of(0L));
        S01ExpectedBase sharedBase = store.current().expectedBase();
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CyclicBarrier startTogether = new CyclicBarrier(WRITERS);

        List<Callable<S01CommitOutcome>> attempts = new ArrayList<>(WRITERS);
        for (int writer = 0; writer < WRITERS; writer++) {
            long seed = writer + 1L;
            attempts.add(() -> {
                startTogether.await(30, TimeUnit.SECONDS);
                return store.commit(sharedBase, S01Policy.of(seed)).outcome();
            });
        }

        try {
            // when
            List<Future<S01CommitOutcome>> futures = pool.invokeAll(attempts);
            List<S01CommitOutcome> outcomes = new ArrayList<>(WRITERS);
            for (Future<S01CommitOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            // then
            assertThat(outcomes).filteredOn(S01CommitOutcome.APPLIED::equals).hasSize(1);
            assertThat(outcomes).filteredOn(S01CommitOutcome.STALE::equals).hasSize(WRITERS - 1);
            assertThat(store.current().acceptedSequence()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
