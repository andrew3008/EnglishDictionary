package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicySnapshotReader;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracingPolicyRefreshCoalescerTest {

    private static final String RUNTIME_INSTANCE_ID = "instance-1";

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

    private static AcceptedTracingPolicySnapshotReader readerReturning(long sequence) {
        AcceptedSnapshotWithSequence accepted =
                new AcceptedSnapshotWithSequence(sequence, RUNTIME_INSTANCE_ID, sampleSnapshot());
        return () -> Optional.of(accepted);
    }

    @Test
    void singleEventTriggersSingleApply() throws Exception {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger applyCount = new AtomicInteger();
        TracingPolicySnapshotClient countingClient = (seq, s, source) -> {
            applyCount.incrementAndGet();
            latch.countDown();
            return TracingPolicyDeliveryResult.APPLIED;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, countingClient, readerReturning(1L), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(applyCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void noRetryOnRejected() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger();
        TracingPolicySnapshotClient rejectingClient = (seq, s, source) -> {
            applyCount.incrementAndGet();
            return TracingPolicyDeliveryResult.REJECTED;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, rejectingClient, readerReturning(1L), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get()).isEqualTo(1); // no retry on REJECTED
    }

    @Test
    void staleTriggersExactlyOneRetry() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger();
        TracingPolicySnapshotClient staleClient = (seq, s, source) -> {
            applyCount.incrementAndGet();
            return TracingPolicyDeliveryResult.STALE;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, staleClient, readerReturning(1L), Runnable::run);
        coalescer.onRefreshEvent();

        assertThat(applyCount.get()).isEqualTo(2); // initial + one bounded retry
    }

    @Test
    void initialDeliveryThrowsOnMissingLeaf() {
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        TracingPolicySnapshotClient client = mock(TracingPolicySnapshotClient.class);
        when(builder.build()).thenThrow(new TracingPolicyManifestException("missing leaf"));

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, client, readerReturning(1L), Runnable::run);

        org.junit.jupiter.api.Assertions.assertThrows(
                TracingPolicyManifestException.class,
                coalescer::deliverInitial);
    }

    @Test
    void initialDeliveryWarmsSequenceFromAgentBeforeApplying() {
        // Proves the fix for the confirmed P4 defect: deliverInitial() must read the
        // Agent-authoritative sequence via currentComplete() before calling applySnapshot,
        // instead of sending an unwarmed expectedSequence=-1 that the real
        // PrivateJmxTracingPolicySnapshotClient would reject before ever reaching the Agent.
        ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger observedSeq = new AtomicInteger(Integer.MIN_VALUE);
        TracingPolicySnapshotClient client = (seq, s, source) -> {
            observedSeq.set((int) seq);
            return TracingPolicyDeliveryResult.APPLIED;
        };

        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                builder, client, readerReturning(7L), Runnable::run);
        TracingPolicyDeliveryResult result = coalescer.deliverInitial();

        assertThat(result).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
        assertThat(observedSeq.get()).isEqualTo(7);
    }

    // ── regression: real PrivateJmxTracingPolicySnapshotClient over a simulated Agent ────
    //
    // The tests above use hand-written lambdas satisfying the exact
    // TracingPolicySnapshotClient / AcceptedTracingPolicySnapshotReader contracts — a
    // legitimate "maximally close contract fake". The tests below additionally exercise the
    // REAL space.br1440.platform.tracing.autoconfigure.configuration.PrivateJmxTracingPolicySnapshotClient
    // (real locator, real codec, real javax.management.MBeanServer.invoke() round trip
    // against FakeAgentPrivateControlPlane), because several of the required invariants —
    // "no expectedSequence=-1 ever reaches the wire", "no unconditional apply on failed
    // read" — are actually enforced INSIDE that client, not inside the coalescer itself,
    // and a coalescer-level lambda fake cannot prove that enforcement is wired end-to-end.

    @Test
    void initialDeliveryWithRealClient_authoritativeReadThenApply_roundTrips() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant("instance-real-1", 5L)) {
            var client = agent.client();
            ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, client, Runnable::run);

            TracingPolicyDeliveryResult result = coalescer.deliverInitial();

            assertThat(result).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence())
                    .as("a real applySnapshot round trip must have reached the fake Agent exactly once")
                    .isEqualTo(6L);
        }
    }

    @Test
    void initialDeliveryWithRealClient_unchangedDelivery_reportsUnchanged() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant("instance-real-2", 9L)) {
            agent.setNextApplyOutcome("UNCHANGED");
            var client = agent.client();
            ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, client, Runnable::run);

            assertThat(coalescer.deliverInitial()).isEqualTo(TracingPolicyDeliveryResult.UNCHANGED);
        }
    }

    @Test
    void initialDeliveryWithRealClient_readFailure_blocksWithoutFallback() throws Exception {
        // Agent never implemented PROPOSED_PRIV_R07: currentComplete() must throw a
        // contract-violation exception, not silently proceed with a fallback/default policy.
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.missingReadAcceptedComplete("instance-real-3")) {
            var client = agent.client();
            ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, client, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .as("a read failure must block delivery, not fall back to a default/unconditional apply")
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void initialDeliveryWithRealClient_malformedResponse_neverSendsUnwarmedSequence() throws Exception {
        // Agent responds to readAcceptedComplete with a structurally malformed CompositeData.
        // PrivateJmxTracingPolicySnapshotClient.currentComplete() swallows this into
        // Optional.empty() (cross-classloader transport-style failure), so lastKnownSequence
        // is never warmed from -1L. The forbidden outcome would be silently sending
        // expectedSequence=-1 to the Agent; instead the REAL client's own guard
        // (no successful currentComplete() read yet -> lastKnownRuntimeInstanceId == null)
        // must reject the apply attempt before anything reaches the wire.
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.malformedReadAcceptedComplete("instance-real-4")) {
            var client = agent.client();
            ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, client, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .as("expectedSequence=-1 must never reach applySnapshot; the real client "
                            + "must fail closed instead")
                    .isInstanceOf(TracingConfigurationGuardException.class)
                    .hasMessageContaining("before any successful currentComplete()");
        }
    }

    @Test
    void realClient_agentRestartWithNewInstanceAndResetSequence_recoversOnNextRead() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant("instance-before-restart", 3L)) {
            var client = agent.client();
            ConfigServerPolicyManifestBuilder builder = mock(ConfigServerPolicyManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingPolicyRefreshCoalescer coalescer =
                    new TracingPolicyRefreshCoalescer(builder, client, client, Runnable::run);
            assertThat(coalescer.deliverInitial()).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence()).isEqualTo(4L);

            // Agent process restarts: fresh runtimeInstanceId, sequence baseline reset to 0.
            agent.restartWithNewInstance("instance-after-restart", 0L);

            // A subsequent refresh cycle must re-warm from the NEW instance's authoritative
            // state (not from the coalescer's stale in-memory lastKnownSequence=4), because
            // deliverInitial()/every apply path re-reads currentComplete() authoritatively
            // rather than trusting cached state across a restart boundary.
            TracingPolicyDeliveryResult postRestart = coalescer.deliverInitial();

            assertThat(postRestart).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
            assertThat(agent.currentAcceptedSequence())
                    .as("apply after restart must be evaluated against the new instance's own sequence, not "
                            + "the pre-restart cached value")
                    .isEqualTo(1L);
        }
    }
}
