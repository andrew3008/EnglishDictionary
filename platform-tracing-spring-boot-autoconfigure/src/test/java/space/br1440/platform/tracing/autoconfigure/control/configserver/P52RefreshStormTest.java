package space.br1440.platform.tracing.autoconfigure.control.configserver;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.CompleteTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P5.2 — Refresh storm and STALE convergence.
 *
 * <p>Verifies the generation/dirty single-worker coalescing contract of
 * {@link TracingPolicyRefreshCoalescer}:
 * <ol>
 *   <li>Burst refresh events while worker busy → exactly one final-generation apply.</li>
 *   <li>Active-worker guard → no parallel worker spawned.</li>
 *   <li>Late-arriving events reschedule exactly once more after the active worker finishes.</li>
 *   <li>Intermediate generations coalesce → serial single-worker execution delivers only
 *       the final generation's state.</li>
 *   <li>Runtime CONFIGSERVER STALE → full authoritative rebuild/read/retry (one bounded).</li>
 *   <li>Sequence is always Agent-authoritative; local cache is never used as the
 *       source-of-truth across STALE/REJECTED/FAILED outcomes.</li>
 *   <li>Retry sequence is never the stale in-memory value: re-read from Agent before retry.</li>
 * </ol>
 *
 * <p>All concurrency proofs use {@link CountDownLatch} and {@link CyclicBarrier} coordination;
 * no {@code Thread.sleep}-only assertions.
 *
 * @see TracingPolicyRefreshCoalescer
 */
class P52RefreshStormTest {

    private static final String RUNTIME_INSTANCE_ID = "instance-p52";

    // -------------------------------------------------------------------------
    // Shared test fixtures
    // -------------------------------------------------------------------------

    private static CompleteTracingPolicySnapshot sampleSnapshot() {
        return CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }

    private static CompleteTracingPolicyReader readerWith(long sequence) {
        AcceptedSnapshotWithSequence snap =
                new AcceptedSnapshotWithSequence(sequence, RUNTIME_INSTANCE_ID, sampleSnapshot());
        return () -> Optional.of(snap);
    }

    /** Reader whose sequence advances each call (simulates Agent sequencing). */
    private static CompleteTracingPolicyReader advancingReader(AtomicLong seq) {
        return () -> Optional.of(
                new AcceptedSnapshotWithSequence(seq.get(), RUNTIME_INSTANCE_ID, sampleSnapshot()));
    }

    // =========================================================================
    // 1. Burst refresh events: single-worker coalescing
    // =========================================================================

    /**
     * Proof item 1 + 3: N rapid {@code onRefreshEvent()} calls while a worker is blocked
     * behind a latch → when the latch is released, exactly one apply cycle runs
     * (delivering the final generation), and a second rescheduled cycle runs because
     * new events arrived during the first cycle. Total apply count ≤ 2.
     */
    @Test
    void burst_events_while_worker_blocked_coalesce_to_at_most_two_cycles() throws Exception {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        // First apply blocks until we open workerGate.
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerGate    = new CountDownLatch(1);
        CountDownLatch allDone       = new CountDownLatch(1);

        AtomicInteger applyCycles = new AtomicInteger(0);

        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            int cycle = applyCycles.incrementAndGet();
            if (cycle == 1) {
                workerStarted.countDown();      // signal: first apply started
                try { workerGate.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (applyCycles.get() >= 2 || generation_drained(allDone)) {
                allDone.countDown();
            }
            return TracingPolicyDeliveryResult.APPLIED;
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, readerWith(1L), pool);

            // Fire first event → spawns worker, which blocks.
            coalescer.onRefreshEvent();
            assertThat(workerStarted.await(3, TimeUnit.SECONDS))
                    .as("worker must start within timeout")
                    .isTrue();

            // Fire burst of 5 more events while worker is inside the first apply.
            for (int i = 0; i < 5; i++) {
                coalescer.onRefreshEvent();
            }

            // Release the blocked first apply.
            workerGate.countDown();

            // Wait for convergence.
            assertThat(allDone.await(5, TimeUnit.SECONDS))
                    .as("all apply cycles must finish within timeout")
                    .isTrue();

            // The burst coalesces: at most 2 apply cycles (first + one rescheduled), never 6.
            assertThat(applyCycles.get())
                    .as("burst of 6 events must coalesce to at most 2 apply cycles, never 6")
                    .isLessThanOrEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    // Helper: avoids extra countDown if already counted down.
    private static boolean generation_drained(CountDownLatch latch) {
        return latch.getCount() == 0;
    }

    /**
     * Proof item 2: {@code scheduleWorkerIfAbsent} is guarded by an {@link java.util.concurrent.atomic.AtomicBoolean}.
     * When the first worker is already executing, a concurrent {@code onRefreshEvent()}
     * must NOT spawn a second parallel worker thread.
     */
    @Test
    void active_worker_guard_prevents_parallel_execution() throws Exception {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        // Barrier: both threads reach the apply body at the same time → if two workers
        // ran in parallel both would unblock; if the guard works only one runs at a time.
        CyclicBarrier barrier = new CyclicBarrier(2 /* would deadlock if only 1 worker */,
                null);
        CountDownLatch anyApplyStarted = new CountDownLatch(1);
        CountDownLatch done            = new CountDownLatch(1);
        AtomicInteger concurrentPeak  = new AtomicInteger(0);
        AtomicInteger active           = new AtomicInteger(0);

        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            int current = active.incrementAndGet();
            concurrentPeak.updateAndGet(v -> Math.max(v, current));
            anyApplyStarted.countDown();
            try {
                // If two workers ran in parallel, both would hit barrier.await() and
                // proceed. If only one runs at a time, barrier.await() would time out.
                barrier.await(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // expected: timeout if single-worker (the guard is working)
            } finally {
                active.decrementAndGet();
                done.countDown();
            }
            return TracingPolicyDeliveryResult.APPLIED;
        };

        // Use a real thread pool so the guard is actually exercised.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, readerWith(1L), pool);

            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();

            assertThat(anyApplyStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();

            assertThat(concurrentPeak.get())
                    .as("at most one worker must be active concurrently (active-worker guard)")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // 2. Intermediate-generation coalescing: N events → single-worker serial execution
    // =========================================================================

    /**
     * Proof item 4: 10 rapid {@code onRefreshEvent()} calls on a synchronous executor
     * (no real threads) → exactly 1 apply for the batched events (the worker re-schedules
     * at most once if generation advanced during the apply, but never 10 times).
     */
    @Test
    void ten_rapid_events_on_sync_executor_coalesce_to_at_most_two_applies() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingPolicyDeliveryResult.APPLIED;
        };

        // Synchronous executor: worker runs inline in the calling thread,
        // so all 10 events accumulate in generation before the first worker executes.
        List<Runnable> deferred = new ArrayList<>();
        Executor deferringExecutor = deferred::add;    // collects, does not run

        TracingPolicyRefreshCoalescer coalescer =
                new TracingPolicyRefreshCoalescer(builder, client, readerWith(1L), deferringExecutor);

        // All 10 events increment generation; only one worker is scheduled.
        for (int i = 0; i < 10; i++) {
            coalescer.onRefreshEvent();
        }
        assertThat(deferred).as("exactly one worker must have been submitted").hasSize(1);

        // Run the deferred worker synchronously.
        deferred.forEach(Runnable::run);

        // Worker saw generation=10 > capturedGeneration=10? No — generation was already 10
        // when worker started; no new events arrived during apply → at most 1 apply total.
        // (If a second reschedule occurred it means generation advanced during apply, which
        // doesn't happen here — but the bound is still ≤2.)
        assertThat(applyCount.get())
                .as("10 rapid events on deferred executor → ≤1 apply cycle")
                .isEqualTo(1);
    }

    /**
     * Proof item 4 (live thread): burst events arriving DURING the active apply
     * are coalesced into exactly one rescheduled apply, not one apply per event.
     */
    @Test
    void events_during_apply_coalesce_to_exactly_one_rescheduled_cycle() throws Exception {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        CountDownLatch firstApplyStarted  = new CountDownLatch(1);
        CountDownLatch injectMoreEvents   = new CountDownLatch(1); // open by test thread
        CountDownLatch allAppliesDone     = new CountDownLatch(2); // expect exactly 2 cycles

        AtomicInteger applyCycles = new AtomicInteger(0);

        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            int cycle = applyCycles.incrementAndGet();
            if (cycle == 1) {
                firstApplyStarted.countDown();
                // wait until test thread injects more events
                try { injectMoreEvents.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            allAppliesDone.countDown();
            return TracingPolicyDeliveryResult.APPLIED;
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, readerWith(1L), pool);

            coalescer.onRefreshEvent();                                  // starts first cycle
            assertThat(firstApplyStarted.await(3, TimeUnit.SECONDS)).isTrue();

            // Inject 7 more events while first apply is executing.
            for (int i = 0; i < 7; i++) {
                coalescer.onRefreshEvent();
            }
            injectMoreEvents.countDown();    // let first apply finish

            assertThat(allAppliesDone.await(5, TimeUnit.SECONDS))
                    .as("exactly 2 apply cycles must complete")
                    .isTrue();

            assertThat(applyCycles.get())
                    .as("7 burst events during first apply coalesce into exactly 1 rescheduled cycle")
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // 3. STALE convergence: runtime CONFIGSERVER STALE → authoritative rebuild/retry
    // =========================================================================

    /**
     * Proof item 5: runtime refresh returning STALE triggers exactly one bounded retry,
     * re-reads Agent-authoritative sequence before the retry (not the stale local value),
     * and does NOT loop infinitely.
     */
    @Test
    void stale_on_runtime_refresh_triggers_one_bounded_retry_with_authoritative_sequence() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicLong agentSequence = new AtomicLong(5L);
        AtomicInteger applyCount = new AtomicInteger(0);
        List<Long> observedSequences = new ArrayList<>();

        // Warm → STALE → bounded retry APPLIED.
        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            observedSequences.add(expectedBase.acceptedSequence());
            int n = applyCount.incrementAndGet();
            if (n == 1) {
                // Warm lastKnownBase from Agent reader (coalescer starts with no cached base).
                return TracingPolicyDeliveryResult.APPLIED;
            }
            if (n == 2) {
                // Simulate Agent advancing sequence between STALE and retry.
                agentSequence.set(7L);
                return TracingPolicyDeliveryResult.STALE;
            }
            return TracingPolicyDeliveryResult.APPLIED;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, client, advancingReader(agentSequence), Runnable::run);

        coalescer.onRefreshEvent(); // warm → lastKnownSequence = 5
        coalescer.onRefreshEvent(); // STALE(5) + retry(7)

        assertThat(applyCount.get())
                .as("warm + STALE + one bounded retry = 3 apply calls")
                .isEqualTo(3);

        assertThat(observedSequences).hasSize(3);

        assertThat(observedSequences.get(1))
                .as("STALE attempt must use warmed Agent-authoritative sequence")
                .isEqualTo(5L);

        assertThat(observedSequences.get(2))
                .as("bounded retry must use re-read Agent sequence (7), not stale local cache (5)")
                .isEqualTo(7L);
    }

    /**
     * Proof item 5: STALE on the bounded retry itself → no third attempt (bounded at 2 total).
     */
    @Test
    void double_stale_stops_at_bounded_retry_no_infinite_loop() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingPolicySnapshotApplier alwaysStale = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingPolicyDeliveryResult.STALE;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, alwaysStale, readerWith(1L), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("STALE on both attempts must not loop; exactly 2 total calls")
                .isEqualTo(2);
    }

    // =========================================================================
    // 4. Sequence sourced from Agent, not local cache
    // =========================================================================

    /**
     * Proof item 6: after REJECTED outcome, {@code lastKnownSequence} is NOT updated;
     * the next apply attempt uses the same Agent-authoritative sequence, not a
     * locally-incremented or stale-cached one.
     */
    @Test
    void rejected_does_not_update_local_sequence_cache() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        List<Long> observedSequences = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        // Warm APPLIED → REJECTED → next APPLIED (sequence must stay at Agent value 3).
        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            observedSequences.add(expectedBase.acceptedSequence());
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return TracingPolicyDeliveryResult.APPLIED;
            }
            if (n == 2) {
                return TracingPolicyDeliveryResult.REJECTED;
            }
            return TracingPolicyDeliveryResult.APPLIED;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, client, readerWith(3L), Runnable::run);

        coalescer.onRefreshEvent(); // warm → lastKnownSequence = 3
        coalescer.onRefreshEvent(); // REJECTED → no sequence update
        coalescer.onRefreshEvent(); // next event → still seq=3

        assertThat(observedSequences).hasSize(3);
        assertThat(observedSequences.get(1)).isEqualTo(3L);
        assertThat(observedSequences.get(2)).isEqualTo(3L);
    }

    /**
     * Proof item 6: after FAILED outcome, sequence is also not updated →
     * subsequent apply uses same Agent-read value.
     */
    @Test
    void failed_does_not_update_local_sequence_cache() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        List<Long> observedSequences = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            observedSequences.add(expectedBase.acceptedSequence());
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return TracingPolicyDeliveryResult.APPLIED;
            }
            if (n == 2) {
                return TracingPolicyDeliveryResult.FAILED;
            }
            return TracingPolicyDeliveryResult.APPLIED;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, client, readerWith(9L), Runnable::run);

        coalescer.onRefreshEvent(); // warm → lastKnownSequence = 9
        coalescer.onRefreshEvent(); // FAILED → no sequence update
        coalescer.onRefreshEvent(); // next event → still seq=9

        assertThat(observedSequences).hasSize(3);
        assertThat(observedSequences.get(1)).isEqualTo(9L);
        assertThat(observedSequences.get(2)).isEqualTo(9L);
    }

    /**
     * Proof item 6: manifest build failure during runtime refresh
     * → apply is NOT called, LKG retained (no sequence corruption).
     */
    @Test
    void manifest_build_failure_on_runtime_refresh_retains_lkg_and_skips_apply() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenThrow(new TracingPolicyManifestException("leaf missing"));

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingPolicyDeliveryResult.APPLIED;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, client, readerWith(2L), Runnable::run);

        coalescer.onRefreshEvent();  // build fails → apply must NOT be called

        assertThat(applyCount.get())
                .as("applySnapshot must not be called when manifest build throws")
                .isEqualTo(0);
    }

    // =========================================================================
    // 5. Retry sequence Agent-authoritative (proof item 7)
    // =========================================================================

    /**
     * Proof item 7: end-to-end with a real {@link FakeAgentPrivateControlPlane}.
     * After STALE the coalescer must re-read {@code currentComplete()} from the Agent
     * (not use the stale local lastKnownSequence) before retrying applySnapshot.
     * The Agent advances its sequence on APPLIED; the subsequent re-read must reflect
     * that advanced value, not the pre-STALE local copy.
     */
    @Test
    void stale_retry_sequence_is_reread_from_real_agent_not_local_cache() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant(RUNTIME_INSTANCE_ID, 10L)) {

            var client = agent.client();
            ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, client, Runnable::run);

            // deliverInitial warms lastKnownSequence from Agent (seq=10 → 11 on APPLIED).
            assertThat(coalescer.deliverInitial()).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence()).isEqualTo(11L);

            // One-shot STALE, then sticky default APPLIED on the bounded retry.
            agent.setOneShotApplyOutcome("STALE");
            coalescer.onRefreshEvent();

            assertThat(agent.currentAcceptedSequence())
                    .as("sequence must advance on the APPLIED bounded retry after one-shot STALE")
                    .isEqualTo(12L);
        }
    }

    /**
     * Proof item 7: the coalescer must NEVER send expectedSequence=-1 on a runtime
     * refresh — even when its local {@code lastKnownSequence} was never set.
     * The real {@link FakeAgentPrivateControlPlane} (malformed reader) exercises
     * the contract-violation guard inside the real
     * {@code PrivateJmxTracingPolicySnapshotClient}.
     */
    @Test
    void runtime_refresh_never_sends_unwarmed_sequence_minus_one() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.malformedReadAcceptedComplete(RUNTIME_INSTANCE_ID)) {

            var client = agent.client();
            ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            // Use the real client as both snapshotClient AND snapshotReader so that
            // the currentComplete() call fails (malformed), which means the coalescer
            // itself must refuse to send an unwarmed expected base.
            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, client, Runnable::run);

            // We expect the coalescer to fail closed with an IllegalStateException,
            // because ExpectedTracingPolicyBase has no valid "unknown" sentinel and the
            // authoritative read from the Agent never succeeded.
            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class,
                    coalescer::deliverInitial,
                    "real client must refuse to send an unwarmed expected base to Agent"
            );
        }
    }

    // =========================================================================
    // 6. Concurrency: no generation loss under parallel event producers
    // =========================================================================

    /**
     * Stress proof: N producer threads each fire M events while a single-threaded
     * pool coalesces them. After all producers finish and the pool drains,
     * the apply count must be much less than N*M (coalescing happened),
     * and apply count must be ≥1 (at least one delivery occurred).
     */
    @Test
    void parallel_producers_coalesce_to_far_fewer_applies_than_total_events() throws Exception {
        final int PRODUCERS  = 8;
        final int EVENTS_EACH = 50;
        final int TOTAL       = PRODUCERS * EVENTS_EACH;

        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingPolicySnapshotApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingPolicyDeliveryResult.APPLIED;
        };

        ExecutorService workerPool    = Executors.newSingleThreadExecutor();
        ExecutorService producerPool  = Executors.newFixedThreadPool(PRODUCERS);
        try {
            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, readerWith(1L), workerPool);

            CountDownLatch producersDone = new CountDownLatch(PRODUCERS);
            for (int p = 0; p < PRODUCERS; p++) {
                producerPool.submit(() -> {
                    for (int e = 0; e < EVENTS_EACH; e++) {
                        coalescer.onRefreshEvent();
                    }
                    producersDone.countDown();
                });
            }

            assertThat(producersDone.await(10, TimeUnit.SECONDS))
                    .as("all producers must finish within timeout")
                    .isTrue();

            // Drain the worker pool.
            workerPool.shutdown();
            assertThat(workerPool.awaitTermination(5, TimeUnit.SECONDS))
                    .as("worker pool must drain within timeout")
                    .isTrue();

            assertThat(applyCount.get())
                    .as("at least one apply must have occurred")
                    .isGreaterThanOrEqualTo(1);
            assertThat(applyCount.get())
                    .as("coalescing must reduce " + TOTAL + " events to far fewer applies")
                    .isLessThan(TOTAL / 2);
        } finally {
            producerPool.shutdownNow();
            if (!workerPool.isShutdown()) workerPool.shutdownNow();
        }
    }
}
