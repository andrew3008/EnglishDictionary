package space.br1440.platform.tracing.autoconfigure.control.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P5.3 — Config Server initial fail-closed and runtime outage LKG retention.
 *
 * <p>Verifies plan §22 invariants for {@code p5-config-server-outage-tests}:
 * <ol>
 *   <li>Initial fail-closed: missing/bad manifest → application readiness blocked.</li>
 *   <li>Initial fail-closed: REJECTED/FAILED outcome → no accepted state established.</li>
 *   <li>Runtime outage: manifest build failure → applySnapshot never called, LKG retained.</li>
 *   <li>Runtime outage: REJECTED/FAILED outcome → LKG retained, sequence unchanged.</li>
 *   <li>Adapter fan-out: non-policy EnvironmentChangeEvent keys do NOT trigger coalescer.</li>
 *   <li>Adapter fan-out: policy keys trigger coalescer exactly once per event.</li>
 *   <li>E2E via real {@link FakeAgentPrivateControlPlane}: post-initial outage cannot degrade
 *       the accepted sequence below the post-initial value.</li>
 * </ol>
 *
 * <p>These tests are deterministic; all coalescer calls use the synchronous
 * ({@code Runnable::run}) executor.
 *
 * @see TracingSettingsRefreshCoalescer
 * @see ConfigServerTracingSettingsAdapter
 * @see ConfigServerSettingsManifestBuilder
 */
class P53ConfigServerOutageTest {

    private static final String RUNTIME_INSTANCE_ID = "instance-p53";

    // -------------------------------------------------------------------------
    // Shared test fixtures
    // -------------------------------------------------------------------------

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
        AcceptedSettingsWithSequence snap =
                new AcceptedSettingsWithSequence(sequence, RUNTIME_INSTANCE_ID, sampleSnapshot());
        return () -> Optional.of(snap);
    }

    private static CompleteTracingSettingsReader advancingReader(AtomicLong seq) {
        return () -> Optional.of(
                new AcceptedSettingsWithSequence(seq.get(), RUNTIME_INSTANCE_ID, sampleSnapshot()));
    }

    // =========================================================================
    // SECTION 1 — Initial fail-closed
    // =========================================================================

    /**
     * P5.3 proof item 1a: missing required Category A leaf during initial load.
     * Plan §12 C-01 (initial phase): candidate REJECTED; application configuration
     * readiness BLOCKED.
     * <p>
     * {@code deliverInitial()} MUST propagate the {@link TracingSettingsManifestException}
     * so that the startup sequence can observe and block readiness.
     * It MUST NOT swallow the exception and silently proceed.
     */
    @Test
    void initial_missing_leaf_propagates_exception_and_blocks_readiness() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenThrow(
                new TracingSettingsManifestException("Required Category A policy key missing: "
                        + "'platform.tracing.policy.sampling.enabled'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerWith(1L), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("missing leaf MUST propagate TracingSettingsManifestException, blocking readiness")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("platform.tracing.policy.sampling.enabled");

        assertThat(applyCount.get())
                .as("applySnapshot MUST NOT be called when manifest is incomplete")
                .isEqualTo(0);
    }

    /**
     * P5.3 proof item 1b: local-override provenance detected during initial load.
     * Local-override keys are forbidden by §12 (fail-closed rejection).
     */
    @Test
    void initial_local_override_leaf_propagates_exception_and_blocks_readiness() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenThrow(
                new TracingSettingsManifestException(
                        "Local override rejected for policy key "
                                + "'platform.tracing.policy.sampling.ratio'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerWith(1L), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("local override MUST propagate TracingSettingsManifestException")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("Local override rejected");

        assertThat(applyCount.get()).isEqualTo(0);
    }

    /**
     * P5.3 proof item 1c: unknown-provenance key during initial load.
     */
    @Test
    void initial_unknown_provenance_propagates_exception_and_blocks_readiness() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenThrow(
                new TracingSettingsManifestException(
                        "Unknown property source for key "
                                + "'platform.tracing.policy.exporter.enabled'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerWith(1L), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("unknown provenance MUST propagate TracingSettingsManifestException")
                .isInstanceOf(TracingSettingsManifestException.class)
                .hasMessageContaining("Unknown property source");

        assertThat(applyCount.get()).isEqualTo(0);
    }

    /**
     * P5.3 proof item 2a: Agent returns REJECTED on initial delivery.
     * <p>
     * Plan §12: initial load REJECTED → application configuration readiness BLOCKED.
     * {@code deliverInitial()} MUST return {@code REJECTED} so the caller can observe
     * and block readiness. It MUST NOT silently swallow the outcome.
     * Accepted state (LKG) is NEVER established.
     */
    @Test
    void initial_rejected_outcome_returned_to_caller_and_no_state_established() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier rejectingClient = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.REJECTED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, rejectingClient, readerWith(1L), Runnable::run);

        TracingSettingsDeliveryResult result = coalescer.deliverInitial();

        assertThat(result)
                .as("REJECTED outcome MUST be returned to caller so readiness can be blocked")
                .isEqualTo(TracingSettingsDeliveryResult.REJECTED);

        assertThat(applyCount.get())
                .as("exactly one apply attempt MUST have been made")
                .isEqualTo(1);
    }

    /**
     * P5.3 proof item 2b: Agent returns FAILED on initial delivery.
     * FAILED → application configuration readiness BLOCKED.
     */
    @Test
    void initial_failed_outcome_returned_to_caller_and_no_state_established() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        TracingSettingsApplier failingClient = (expectedBase, s, source) ->
                TracingSettingsDeliveryResult.FAILED;

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, failingClient, readerWith(1L), Runnable::run);

        TracingSettingsDeliveryResult result = coalescer.deliverInitial();

        assertThat(result)
                .as("FAILED outcome MUST be returned to caller so readiness can be blocked")
                .isEqualTo(TracingSettingsDeliveryResult.FAILED);
    }

    /**
     * P5.3 proof item 4: private-writer client throws during initial delivery.
     * <p>
     * If {@code applySnapshot} throws (writer unavailable, JMX transport error, etc.),
     * the exception MUST propagate out of {@code deliverInitial()} so startup can
     * fail-closed. No silent swallowing, no fallback.
     */
    @Test
    void initial_writer_throws_exception_propagates_out_of_deliver_initial() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        TracingSettingsApplier throwingClient = (expectedBase, s, source) -> {
            throw new RuntimeException("JMX transport unavailable");
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, throwingClient, readerWith(1L), Runnable::run);

        assertThatThrownBy(coalescer::deliverInitial)
                .as("writer exception MUST propagate; deliverInitial() must NOT swallow it")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JMX transport unavailable");
    }

    // =========================================================================
    // SECTION 2 — Runtime outage: LKG retained
    // =========================================================================

    /**
     * P5.3 proof item 5: manifest build failure during a runtime refresh.
     * <p>
     * Plan §12 C-01 (runtime phase): candidate REJECTED; accepted policy UNCHANGED;
     * {@code acceptedSequence} UNCHANGED; LKG retained.
     * <p>
     * The coalescer MUST:
     * <ul>
     *   <li>NOT call {@code applySnapshot};</li>
     *   <li>NOT throw or crash (runtime outage is non-fatal);</li>
     *   <li>leave {@code lastKnownSequence} at its pre-outage value.</li>
     * </ul>
     */
    @Test
    void runtime_manifest_build_failure_skips_apply_and_retains_lkg() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);

        // First call (initial): succeeds.
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException(
                        "Config Server connection lost; required key missing"));

        AtomicLong capturedSeq = new AtomicLong(-99L);
        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            capturedSeq.set(expectedBase.acceptedSequence());
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        AtomicLong agentSeq = new AtomicLong(3L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, advancingReader(agentSeq), Runnable::run);

        // Successful initial delivery warms lastKnownSequence to 3.
        assertThat(coalescer.deliverInitial()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(applyCount.get()).isEqualTo(1);
        long seqAfterInitial = capturedSeq.get();
        assertThat(seqAfterInitial).isEqualTo(3L);

        // Simulate runtime outage: builder throws on second call.
        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("applySnapshot MUST NOT be called when runtime manifest build fails")
                .isEqualTo(1); // no second call
    }

    /**
     * P5.3 proof item 5b: local-override provenance during runtime refresh
     * → apply skipped, LKG retained.
     */
    @Test
    void runtime_local_override_detected_skips_apply_and_retains_lkg() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException(
                        "Local override rejected for policy key "
                                + "'platform.tracing.policy.validation.strict'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerWith(2L), Runnable::run);

        coalescer.deliverInitial();
        assertThat(applyCount.get()).isEqualTo(1);

        coalescer.onRefreshEvent(); // triggers outage

        assertThat(applyCount.get())
                .as("local override during runtime refresh MUST skip apply")
                .isEqualTo(1);
    }

    /**
     * P5.3 proof item 5c: unknown-provenance key during runtime refresh
     * → apply skipped, LKG retained.
     */
    @Test
    void runtime_unknown_provenance_skips_apply_and_retains_lkg() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException(
                        "Unknown property source for key "
                                + "'platform.tracing.policy.diagnostics.log-level'"));

        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerWith(4L), Runnable::run);

        coalescer.deliverInitial();
        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("unknown provenance during runtime refresh MUST skip apply")
                .isEqualTo(1);
    }

    /**
     * P5.3 proof item 6: Agent returns REJECTED during a runtime refresh.
     * <p>
     * Plan §12 C-01 (runtime phase): REJECTED → accepted policy UNCHANGED;
     * {@code acceptedSequence} UNCHANGED; LKG retained.
     * The coalescer MUST NOT update its local sequence cache on REJECTED.
     */
    @Test
    void runtime_rejected_retains_lkg_and_does_not_update_sequence() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger callN     = new AtomicInteger(0);
        List<Long>   seenSeqs  = new ArrayList<>();

        // Call 1 (initial): APPLIED at seq=5. Call 2+: REJECTED.
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            if (callN.incrementAndGet() == 1) return TracingSettingsDeliveryResult.APPLIED;
            return TracingSettingsDeliveryResult.REJECTED;
        };

        AtomicLong agentSeq = new AtomicLong(5L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, advancingReader(agentSeq), Runnable::run);

        coalescer.deliverInitial();        // APPLIED, warms lastKnownSequence to 5
        // Agent may advance independently; REJECTED must NOT pull that into lastKnownSequence.
        agentSeq.set(6L);

        coalescer.onRefreshEvent();        // REJECTED — LKG retained
        coalescer.onRefreshEvent();        // REJECTED again — still LKG retained

        assertThat(callN.get())
                .as("exactly 3 apply calls: 1 initial + 2 runtime")
                .isEqualTo(3);

        // Runtime refresh uses lastKnownSequence (warmed at 5). REJECTED does not update it,
        // so both calls keep presenting 5 even though the Agent reader now reports 6.
        assertThat(seenSeqs.get(1))
                .as("first REJECTED runtime call must keep warmed LKG sequence")
                .isEqualTo(5L);
        assertThat(seenSeqs.get(2))
                .as("second REJECTED call must still use unchanged LKG sequence")
                .isEqualTo(5L);
    }

    /**
     * P5.3 proof item 7: Agent returns FAILED during a runtime refresh.
     * FAILED → accepted policy UNCHANGED; LKG retained; sequence NOT updated.
     */
    @Test
    void runtime_failed_retains_lkg_and_does_not_update_sequence() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger callN    = new AtomicInteger(0);
        List<Long>   seenSeqs = new ArrayList<>();

        TracingSettingsApplier client = (expectedBase, s, source) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            if (callN.incrementAndGet() == 1) return TracingSettingsDeliveryResult.APPLIED;
            return TracingSettingsDeliveryResult.FAILED;
        };

        AtomicLong agentSeq = new AtomicLong(10L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, advancingReader(agentSeq), Runnable::run);

        coalescer.deliverInitial(); // warms lastKnownSequence to 10
        agentSeq.set(11L);

        coalescer.onRefreshEvent();  // FAILED — LKG retained
        coalescer.onRefreshEvent();  // FAILED again

        assertThat(callN.get()).isEqualTo(3);

        assertThat(seenSeqs.get(1))
                .as("first FAILED runtime call must keep warmed LKG sequence")
                .isEqualTo(10L);
        assertThat(seenSeqs.get(2))
                .as("second FAILED call: seq MUST NOT change after FAILED")
                .isEqualTo(10L);
    }

    /**
     * P5.3 proof item 12: multiple sequential runtime outages (builder throws).
     * Each outage fails independently; sequence remains stable across all of them.
     */
    @Test
    void multiple_sequential_runtime_outages_sequence_stable_throughout() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        // First call: good; all subsequent: outage.
        when(builder.build())
                .thenReturn(sampleSnapshot())
                .thenThrow(new TracingSettingsManifestException("outage 1"))
                .thenThrow(new TracingSettingsManifestException("outage 2"))
                .thenThrow(new TracingSettingsManifestException("outage 3"));

        AtomicInteger applyCount = new AtomicInteger(0);
        List<Long>    seenSeqs   = new ArrayList<>();
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, readerWith(7L), Runnable::run);

        coalescer.deliverInitial();
        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();
        coalescer.onRefreshEvent();

        assertThat(applyCount.get())
                .as("only the initial apply must have occurred; 3 runtime outages skip apply")
                .isEqualTo(1);
        assertThat(seenSeqs)
                .as("only one sequence observation from the successful initial delivery")
                .containsExactly(7L);
    }

    /**
     * P5.3 proof item 7 (partial-outage): a successful refresh after an outage
     * advances the sequence normally; the outage leaves no negative side-effect.
     */
    @Test
    void successful_refresh_after_outage_advances_sequence_normally() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        // Call pattern: GOOD, OUTAGE, GOOD.
        when(builder.build())
                .thenReturn(sampleSnapshot())                                         // initial
                .thenThrow(new TracingSettingsManifestException("transient outage"))    // runtime 1
                .thenReturn(sampleSnapshot());                                         // runtime 2

        List<Long>   seenSeqs  = new ArrayList<>();
        AtomicInteger applyCount = new AtomicInteger(0);
        TracingSettingsApplier client = (expectedBase, s, source) -> {
            seenSeqs.add(expectedBase.acceptedSequence());
            applyCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        AtomicLong agentSeq = new AtomicLong(2L);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, client, advancingReader(agentSeq), Runnable::run);

        coalescer.deliverInitial();   // APPLIED; lastKnownSequence=2
        agentSeq.set(3L);             // Agent advances independently during outage window

        coalescer.onRefreshEvent();   // OUTAGE — apply skipped; lastKnownSequence stays 2
        coalescer.onRefreshEvent();   // GOOD   — apply still presents warmed seq=2; then refreshes LKG to 3

        assertThat(applyCount.get())
                .as("exactly 2 apply calls: initial + post-outage recovery")
                .isEqualTo(2);
        assertThat(seenSeqs)
                .as("outage must not corrupt lastKnownSequence; recovery apply uses warmed LKG")
                .containsExactly(2L, 2L);

        // After recovery APPLIED, a further refresh must observe the Agent-advanced sequence.
        coalescer.onRefreshEvent();
        assertThat(applyCount.get()).isEqualTo(3);
        assertThat(seenSeqs.get(2))
                .as("post-recovery refresh must use Agent sequence refreshed after APPLIED")
                .isEqualTo(3L);
    }

    // =========================================================================
    // SECTION 3 — E2E via real FakeAgentPrivateControlPlane
    // =========================================================================

    /**
     * P5.3 proof item 10 (E2E): successful initial apply followed by runtime outage.
     * <p>
     * After {@code deliverInitial()} applies at sequence N, a subsequent runtime
     * manifest-build failure MUST leave the Agent's accepted sequence at N; it
     * MUST NOT decrement, reset, or corrupt the sequence.
     */
    @Test
    void e2e_runtime_outage_after_initial_success_does_not_degrade_agent_sequence() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant(RUNTIME_INSTANCE_ID, 0L)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            // First call: good; second: outage.
            when(builder.build())
                    .thenReturn(sampleSnapshot())
                    .thenThrow(new TracingSettingsManifestException("e2e outage"));

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            TracingSettingsDeliveryResult initial = coalescer.deliverInitial();
            assertThat(initial).isEqualTo(TracingSettingsDeliveryResult.APPLIED);

            long seqAfterInitial = agent.currentAcceptedSequence();
            assertThat(seqAfterInitial)
                    .as("sequence must have advanced after initial APPLIED")
                    .isGreaterThan(0L);

            // Runtime outage: build throws — apply is skipped.
            coalescer.onRefreshEvent();

            assertThat(agent.currentAcceptedSequence())
                    .as("runtime outage MUST NOT degrade Agent sequence below post-initial value")
                    .isEqualTo(seqAfterInitial);
        }
    }

    /**
     * P5.3 proof item 11 (E2E): initial REJECTED from real Agent.
     * <p>
     * Agent rejects the first snapshot (simulated via {@code setNextApplyOutcome("REJECTED")}).
     * The accepted sequence in the Agent MUST remain at the baseline; no increment.
     */
    @Test
    void e2e_initial_rejected_leaves_agent_sequence_at_baseline() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant(RUNTIME_INSTANCE_ID, 5L)) {

            agent.setNextApplyOutcome("REJECTED");
            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(sampleSnapshot());

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            TracingSettingsDeliveryResult result = coalescer.deliverInitial();

            assertThat(result)
                    .as("REJECTED must be returned to caller")
                    .isEqualTo(TracingSettingsDeliveryResult.REJECTED);

            assertThat(agent.currentAcceptedSequence())
                    .as("REJECTED MUST NOT increment Agent sequence")
                    .isEqualTo(5L);
        }
    }

    /**
     * P5.3 E2E: multiple consecutive runtime outages; Agent sequence stable throughout.
     */
    @Test
    void e2e_multiple_runtime_outages_agent_sequence_stable() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant(RUNTIME_INSTANCE_ID, 1L)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build())
                    .thenReturn(sampleSnapshot())
                    .thenThrow(new TracingSettingsManifestException("outage A"))
                    .thenThrow(new TracingSettingsManifestException("outage B"))
                    .thenThrow(new TracingSettingsManifestException("outage C"));

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            coalescer.deliverInitial();
            long seqAfterInitial = agent.currentAcceptedSequence();

            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();

            assertThat(agent.currentAcceptedSequence())
                    .as("3 consecutive runtime outages MUST leave Agent sequence unchanged")
                    .isEqualTo(seqAfterInitial);
        }
    }

    /**
     * P5.3 E2E: recovery after multiple outages reaches the Agent correctly.
     */
    @Test
    void e2e_recovery_after_multiple_outages_applies_to_agent() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant(RUNTIME_INSTANCE_ID, 10L)) {

            var client = agent.client();
            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build())
                    .thenReturn(sampleSnapshot())                                          // initial
                    .thenThrow(new TracingSettingsManifestException("outage 1"))              // refresh 1
                    .thenThrow(new TracingSettingsManifestException("outage 2"))              // refresh 2
                    .thenReturn(sampleSnapshot());                                          // refresh 3 (recovery)

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, client, Runnable::run);

            coalescer.deliverInitial();
            long seqAfterInitial = agent.currentAcceptedSequence();

            coalescer.onRefreshEvent(); // outage
            coalescer.onRefreshEvent(); // outage
            coalescer.onRefreshEvent(); // recovery

            assertThat(agent.currentAcceptedSequence())
                    .as("recovery after outages MUST advance Agent sequence beyond post-initial value")
                    .isGreaterThan(seqAfterInitial);
        }
    }

    // =========================================================================
    // SECTION 4 — ConfigServerTracingSettingsAdapter fan-out
    // =========================================================================

    /**
     * P5.3 proof item 13: non-policy EnvironmentChangeEvent keys MUST NOT trigger coalescer.
     * <p>
     * Plan §12: "EnvironmentChangeEvent as observation trigger (keys — triggers only)".
     * Events carrying keys outside {@code platform.tracing.policy.*} are irrelevant
     * and MUST be silently ignored.
     */
    @Test
    void adapter_non_policy_keys_do_not_trigger_coalescer() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger refreshCount = new AtomicInteger(0);
        TracingSettingsApplier countingClient = (expectedBase, s, source) -> {
            refreshCount.incrementAndGet();
            return TracingSettingsDeliveryResult.APPLIED;
        };

        // Wrap coalescer in a counting proxy to detect onRefreshEvent() calls.
        AtomicInteger onRefreshCalls = new AtomicInteger(0);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder, countingClient, readerWith(1L), task -> {
                    onRefreshCalls.incrementAndGet();
                    task.run();
                });

        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(coalescer);

        // Fire events with NON-policy keys only.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("spring.datasource.url", "management.endpoints.enabled")));
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("logging.level.root")));
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("platform.tracing.javaagent.sampling.ratio")));  // NOT policy.*

        assertThat(onRefreshCalls.get())
                .as("non-policy EnvironmentChangeEvent keys MUST NOT trigger coalescer")
                .isEqualTo(0);
    }

    /**
     * P5.3 proof item 14: policy-namespace EnvironmentChangeEvent keys trigger
     * coalescer exactly once per event, regardless of how many policy keys changed.
     */
    @Test
    void adapter_policy_keys_trigger_coalescer_exactly_once_per_event() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger onRefreshCalls = new AtomicInteger(0);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                (expectedBase, s, source) -> TracingSettingsDeliveryResult.APPLIED,
                readerWith(1L),
                task -> {
                    onRefreshCalls.incrementAndGet();
                    task.run();
                });

        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(coalescer);

        // Single event with one policy key.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("platform.tracing.policy.sampling.enabled")));
        assertThat(onRefreshCalls.get())
                .as("one policy key in event → exactly one coalescer trigger")
                .isEqualTo(1);

        // Single event with multiple policy keys.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of(
                        "platform.tracing.policy.sampling.ratio",
                        "platform.tracing.policy.validation.enabled",
                        "platform.tracing.policy.exporter.enabled")));
        assertThat(onRefreshCalls.get())
                .as("multiple policy keys in one event → still exactly one more coalescer trigger")
                .isEqualTo(2);

        // Mixed event: policy + non-policy keys together.
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of(
                        "spring.cloud.config.revision",               // non-policy
                        "platform.tracing.policy.diagnostics.log-level")));
        assertThat(onRefreshCalls.get())
                .as("mixed event with at least one policy key → exactly one more coalescer trigger")
                .isEqualTo(3);
    }

    /**
     * P5.3 adapter: event with no keys (empty set) MUST NOT trigger coalescer.
     */
    @Test
    void adapter_empty_key_set_does_not_trigger_coalescer() {
        ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
        when(builder.build()).thenReturn(sampleSnapshot());

        AtomicInteger onRefreshCalls = new AtomicInteger(0);
        TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                builder,
                (expectedBase, s, source) -> TracingSettingsDeliveryResult.APPLIED,
                readerWith(1L),
                task -> {
                    onRefreshCalls.incrementAndGet();
                    task.run();
                });

        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(coalescer);
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(Set.of()));

        assertThat(onRefreshCalls.get())
                .as("empty EnvironmentChangeEvent MUST NOT trigger coalescer")
                .isEqualTo(0);
    }
}
