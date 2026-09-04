package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class RefreshStormTest {

    private static final String RUNTIME_INSTANCE_ID = "instance-refresh-storm";

    private static CompleteTracingSettings sampleSnapshot() {
        return CompleteTracingSettings.builder()
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

    private static CompleteTracingSettingsReader readerWith(long sequence) {
        AcceptedSettingsWithSequence snap = new AcceptedSettingsWithSequence(sequence, RUNTIME_INSTANCE_ID, sampleSnapshot());
        return () -> Optional.of(snap);
    }

    private static CompleteTracingSettingsReader advancingReader(AtomicLong seq) {
        return () -> Optional.of(
                new AcceptedSettingsWithSequence(seq.get(), RUNTIME_INSTANCE_ID, sampleSnapshot())
        );
    }

    // =========================================================================
    // 1. Burst refresh events: single-worker coalescing
    // =========================================================================

    @Test
    void burst_events_while_worker_blocked_coalesce_to_at_most_two_cycles() throws Exception {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerGate = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(1);

        AtomicInteger applyCycles = new AtomicInteger(0);

        TracingSettingsApplier client = (expectedBase, s, source) -> {
            int cycle = applyCycles.incrementAndGet();
            if (cycle == 1) {
                workerStarted.countDown();
                try {
                    if (!workerGate.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("workerGate release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (applyCycles.get() >= 2 || generation_drained(allDone)) {
                allDone.countDown();
            }

            return TracingSettingsDeliveryResult.APPLIED;
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(builder, client, readerWith(1L), pool);

            coalescer.onRefreshEvent();
            assertThat(workerStarted.await(3, TimeUnit.SECONDS))
                    .as("worker must start within timeout")
                    .isTrue();

            for (int i = 0; i < 5; i++) {
                coalescer.onRefreshEvent();
            }

            workerGate.countDown();

            assertThat(allDone.await(5, TimeUnit.SECONDS))
                    .as("all apply cycles must finish within timeout")
                    .isTrue();

            assertThat(applyCycles.get())
                    .as("burst of 6 events must coalesce to at most 2 apply cycles, never 6")
                    .isLessThanOrEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean generation_drained(CountDownLatch latch) {
        return latch.getCount() == 0;
    }

    @Test
    void active_worker_guard_prevents_parallel_execution() throws Exception {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        CyclicBarrier barrier = new CyclicBarrier(2, null);
        CountDownLatch anyApplyStarted = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger concurrentPeak = new AtomicInteger(0);
        AtomicInteger active = new AtomicInteger(0);

        TracingSettingsApplier client = (expectedBase, s, source) -> {
            int current = active.incrementAndGet();
            concurrentPeak.updateAndGet(v -> Math.max(v, current));
            anyApplyStarted.countDown();
            try {
                barrier.await(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // expected: timeout if single-worker (the guard is working)
            } finally {
                active.decrementAndGet();
                done.countDown();
            }
            return TracingSettingsDeliveryResult.APPLIED;
        };

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, readerWith(1L), pool);

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

    @Test
    void ten_rapid_events_on_sync_executor_coalesce_to_at_most_two_applies() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        List<Runnable> deferred = new ArrayList<>();
        Executor deferringExecutor = deferred::add;    // collects, does not run

        TracingSettingsRefreshCoalescer coalescer =
                new TracingSettingsRefreshCoalescer(builder, client, readerWith(1L), deferringExecutor);

        for (int i = 0; i < 10; i++) {
            coalescer.onRefreshEvent();
        }
        assertThat(deferred).as("exactly one worker must have been submitted").hasSize(1);

        deferred.forEach(Runnable::run);

        assertThat(applyCount.get())
                .as("10 rapid events on deferred executor → ≤1 apply cycle")
                .isEqualTo(1);
    }

    @Test
    void events_during_apply_coalesce_to_exactly_one_rescheduled_cycle() throws Exception {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        CountDownLatch firstApplyStarted = new CountDownLatch(1);
        CountDownLatch injectMoreEvents = new CountDownLatch(1);
        CountDownLatch allAppliesDone = new CountDownLatch(2);

        AtomicInteger applyCycles = new AtomicInteger(0);

        TracingSettingsApplier client = (expectedBase, s, source) -> {
            int cycle = applyCycles.incrementAndGet();
            if (cycle == 1) {
                firstApplyStarted.countDown();

                try {
                    if (!injectMoreEvents.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("injectMoreEvents release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            allAppliesDone.countDown();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(builder, client, readerWith(1L), pool);

            coalescer.onRefreshEvent();
            assertThat(firstApplyStarted.await(3, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < 7; i++) {
                coalescer.onRefreshEvent();
            }
            injectMoreEvents.countDown();

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
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicLong agentSequence = new AtomicLong(5L);
        AtomicInteger applyCount = new AtomicInteger(0);
        List<Long> observedSequences = new ArrayList<>();

        // Warm → STALE → bounded retry APPLIED.
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            observedSequences.add(expectedBase.acceptedSequence());
            int n = applyCount.incrementAndGet();
            if (n == 1) {
                // Warm lastKnownBase from Agent reader (coalescer starts with no cached base).
                return TracingSettingsDeliveryResult.APPLIED;
            }
            if (n == 2) {
                // Simulate Agent advancing sequence between STALE and retry.
                agentSequence.set(7L);
                return TracingSettingsDeliveryResult.STALE;
            }
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier alwaysStale = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.STALE;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        List<Long> observedSequences = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        // Warm APPLIED → REJECTED → next APPLIED (sequence must stay at Agent value 3).
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            observedSequences.add(expectedBase.acceptedSequence());
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return TracingSettingsDeliveryResult.APPLIED;
            }
            if (n == 2) {
                return TracingSettingsDeliveryResult.REJECTED;
            }
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        List<Long> observedSequences = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        TracingSettingsApplier client = (expectedBase, s, source) -> {
            observedSequences.add(expectedBase.acceptedSequence());
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return TracingSettingsDeliveryResult.APPLIED;
            }
            if (n == 2) {
                return TracingSettingsDeliveryResult.FAILED;
            }
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenThrow(new TracingSettingsManifestException("leaf missing"));

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
     * Проверка 7: сквозной сценарий с реальным {@link FakeAgentPlatformJmxPlane}.
     * After STALE the coalescer must re-read {@code currentComplete()} from the Agent
     * (not use the stale local lastKnownSequence) before retrying applySnapshot.
     * The Agent advances its sequence on APPLIED; the subsequent re-read must reflect
     * that advanced value, not the pre-STALE local copy.
     */
    @Test
    void stale_retry_sequence_is_reread_from_real_agent_not_local_cache() throws Exception {
        try (FakeAgentPlatformJmxPlane agent =
                     FakeAgentPlatformJmxPlane.compliant(RUNTIME_INSTANCE_ID, 10L)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            // deliverInitial warms lastKnownSequence from Agent (seq=10 → 11 on APPLIED).
            assertThat(coalescer.deliverInitial()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
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
     * Реальный {@link FakeAgentPlatformJmxPlane} с некорректным чтением проверяет защиту
     * от нарушения контракта внутри настоящего
     * {@code PlatformJmxTracingSettingsClient}.
     */
    @Test
    void runtime_refresh_never_sends_unwarmed_sequence_minus_one() throws Exception {
        try (FakeAgentPlatformJmxPlane agent =
                     FakeAgentPlatformJmxPlane.malformedAcceptedPolicySnapshot(RUNTIME_INSTANCE_ID)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            // Use the real client as both snapshotClient AND snapshotReader so that
            // the currentComplete() call fails (malformed), which means the coalescer
            // itself must refuse to send an unwarmed expected base.
            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            // We expect the coalescer to fail closed with an IllegalStateException,
            // because ExpectedSettingsRevision has no valid "unknown" sentinel and the
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
        final int PRODUCERS = 8;
        final int EVENTS_EACH = 50;
        final int TOTAL = PRODUCERS * EVENTS_EACH;

        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        try (ExecutorService workerPool = Executors.newSingleThreadExecutor();
             ExecutorService producerPool = Executors.newFixedThreadPool(PRODUCERS)) {
            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, readerWith(1L), workerPool);

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
        }
    }
}
