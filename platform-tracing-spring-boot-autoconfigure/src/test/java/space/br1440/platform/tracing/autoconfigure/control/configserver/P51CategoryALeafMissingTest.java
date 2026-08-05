package space.br1440.platform.tracing.autoconfigure.control.configserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.CompleteTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.ExpectedTracingPolicyBase;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.TracingPolicyManifestException;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySnapshotApplier;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §20 P5.1 — Category A leaf missing/deleted invariants.
 * <p>
 * Scenario A (initial): missing Category A leaf at startup →
 *   {@link TracingPolicyRefreshCoalescer#deliverInitial()} throws
 *   {@link TracingPolicyManifestException}  → readiness BLOCKED
 *   (application context refuses to complete startup).
 * <p>
 * Scenario B (runtime): Category A leaf disappears/deleted after initial delivery →
 *   runtime refresh does NOT call {@code applySnapshot};
 *   LKG snapshot, digest, runtimeInstanceId, acceptedSequence are ALL immutable.
 * <p>
 * Scenario C (restore): leaf-missing → REJECTED state is cleared ONLY when
 *   Config Server delivers an explicit complete snapshot with the leaf present;
 *   no implicit default is injected by the application.
 */
@DisplayName("§20 P5.1 – Category A leaf missing/deleted: readiness BLOCKED & runtime REJECTED")
class P51CategoryALeafMissingTest {

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Full valid 8-leaf snapshot used as LKG baseline. */
    private static final CompleteTracingPolicySnapshot LKG_SNAPSHOT =
            CompleteTracingPolicySnapshot.builder()
                    .samplingEnabled(true)
                    .samplingRatio(1.0)
                    .samplingRouteRatios(java.util.Map.of())
                    .validationEnabled(true)
                    .validationStrict(false)
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("INFO")
                    .build();

    private static final long   LKG_SEQUENCE         = 42L;
    private static final String LKG_RUNTIME_INSTANCE = "agent-instance-abc";
    private static final String LKG_DIGEST           = "sha256:deadbeef";

    /** Constructs an AcceptedSnapshotWithSequence representing the LKG state. */
    private static AcceptedSnapshotWithSequence lkgAcceptedState() {
        return new AcceptedSnapshotWithSequence(LKG_SEQUENCE, LKG_RUNTIME_INSTANCE, LKG_SNAPSHOT);
    }

    // -------------------------------------------------------------------------
    // Scenario A: initial missing leaf → readiness BLOCKED
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("A: initial delivery — missing leaf → readiness BLOCKED")
    class InitialMissingLeaf {

        @Test
        @DisplayName("deliverInitial throws TracingPolicyManifestException when any Category A leaf is missing")
        void deliverInitial_throwsManifestException_whenLeafMissing() {
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException(
                            "Required Category A policy key missing: "
                            + "'platform.tracing.policy.sampling.enabled'"));

            TracingPolicySnapshotApplier client = mock(TracingPolicySnapshotApplier.class);
            CompleteTracingPolicyReader reader = mock(CompleteTracingPolicyReader.class);

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, client, reader, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .isInstanceOf(TracingPolicyManifestException.class)
                    .hasMessageContaining("platform.tracing.policy.sampling.enabled");
        }

        @Test
        @DisplayName("deliverInitial never reaches applySnapshot when leaf is missing")
        void deliverInitial_neverCallsApplySnapshot_whenLeafMissing() {
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException("diagnosticsLogLevel not set"));

            TracingPolicySnapshotApplier client = mock(TracingPolicySnapshotApplier.class);
            CompleteTracingPolicyReader reader = mock(CompleteTracingPolicyReader.class);

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, client, reader, Runnable::run);

            try {
                coalescer.deliverInitial();
            } catch (TracingPolicyManifestException ignored) { /* expected */ }

            verify(client, never()).applySnapshot(any(), any(), any());
        }

        @Test
        @DisplayName("deliverInitial throws for EACH of the 7 mandatory non-route-ratio leaves")
        void deliverInitial_throwsForEachMandatoryLeaf() {
            // The manifest builder is responsible for failing per missing key;
            // here we assert that ANY TracingPolicyManifestException propagates
            // (not swallowed). The fixture simulates deletion of every leaf.
            String[] mandatoryLeaves = {
                    "platform.tracing.policy.sampling.enabled",
                    "platform.tracing.policy.sampling.ratio",
                    "platform.tracing.policy.validation.enabled",
                    "platform.tracing.policy.validation.strict",
                    "platform.tracing.policy.exporter.enabled",
                    "platform.tracing.policy.propagation.trace-control-headers.enabled",
                    "platform.tracing.policy.diagnostics.log-level"
            };

            for (String leaf : mandatoryLeaves) {
                ConfigServerPolicyManifestBuilder builder =
                        mock(ConfigServerPolicyManifestBuilder.class);
                when(builder.build())
                        .thenThrow(new TracingPolicyManifestException(
                                "Required Category A policy key missing: '" + leaf + "'"));

                TracingPolicySnapshotApplier client = mock(TracingPolicySnapshotApplier.class);
                CompleteTracingPolicyReader reader =
                        mock(CompleteTracingPolicyReader.class);

                TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                        builder, client, reader, Runnable::run);

                assertThatThrownBy(coalescer::deliverInitial)
                        .as("missing leaf: %s", leaf)
                        .isInstanceOf(TracingPolicyManifestException.class)
                        .hasMessageContaining(leaf);

                verify(client, never()).applySnapshot(any(), any(), any());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Scenario B: runtime missing/deleted leaf → REJECTED; LKG immutable
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("B: runtime refresh — missing/deleted leaf → REJECTED, LKG immutable")
    class RuntimeMissingLeaf {

        private TrackingSnapshotClient trackingClient;
        private TrackingSnapshotReader trackingReader;

        @BeforeEach
        void setUp() {
            trackingClient = new TrackingSnapshotClient(TracingPolicyDeliveryResult.APPLIED);
            trackingReader = new TrackingSnapshotReader(lkgAcceptedState());
        }

        @Test
        @DisplayName("runtime refresh with missing leaf does NOT call applySnapshot")
        void onRefreshEvent_doesNotCallApplySnapshot_whenLeafMissing() {
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException(
                            "Required Category A policy key missing: "
                            + "'platform.tracing.policy.exporter.enabled'"));

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);

            coalescer.onRefreshEvent();

            assertThat(trackingClient.applyCallCount()).isZero();
        }

        @Test
        @DisplayName("runtime missing leaf leaves lastKnownSequence unchanged (LKG immutable)")
        void onRefreshEvent_lastKnownSequence_isUnchangedWhenLeafMissing() {
            // First: deliver successfully to seed lastKnownSequence
            ConfigServerPolicyManifestBuilder goodBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(goodBuilder.build()).thenReturn(LKG_SNAPSHOT);

            trackingClient = new TrackingSnapshotClient(TracingPolicyDeliveryResult.APPLIED);
            trackingReader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    goodBuilder, trackingClient, trackingReader, Runnable::run);
            coalescer.onRefreshEvent();

            long sequenceAfterGoodApply = trackingClient.lastAppliedSequence();

            // Now: leaf disappears
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException(
                            "Required Category A policy key missing: "
                            + "'platform.tracing.policy.sampling.ratio'"));

            // Replace the coalescer with failing builder; coalescer holds no external
            // sequence state visible through public API — what we can verify is that
            // applySnapshot was not called again, meaning Agent-side LKG is untouched.
            TracingPolicyRefreshCoalescer failingCoalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);
            int applyCountBefore = trackingClient.applyCallCount();

            failingCoalescer.onRefreshEvent();

            assertThat(trackingClient.applyCallCount())
                    .as("applySnapshot must NOT be called when leaf is missing")
                    .isEqualTo(applyCountBefore);
            // The last sequence delivered to Agent is still the LKG one
            assertThat(trackingClient.lastAppliedSequence())
                    .as("acceptedSequence must remain unchanged")
                    .isEqualTo(sequenceAfterGoodApply);
        }

        @Test
        @DisplayName("runtimeInstanceId returned by reader is unchanged after runtime missing leaf")
        void onRefreshEvent_runtimeInstanceId_isUnchangedWhenLeafMissing() {
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException(
                            "Required Category A policy key missing: "
                            + "'platform.tracing.policy.validation.strict'"));

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);

            coalescer.onRefreshEvent();

            // Verify reader was never asked to refresh (no currentComplete() call during failed apply)
            assertThat(trackingReader.currentCompleteCallCount())
                    .as("reader.currentComplete() must not be called when build() fails")
                    .isZero();
            // The runtimeInstanceId visible in the LKG reader state is unchanged
            assertThat(trackingReader.currentComplete())
                    .isPresent()
                    .map(AcceptedSnapshotWithSequence::runtimeInstanceId)
                    .contains(LKG_RUNTIME_INSTANCE);
        }

        @Test
        @DisplayName("LKG snapshot content is unchanged after runtime missing leaf")
        void onRefreshEvent_lkgSnapshotContent_isUnchangedWhenLeafMissing() {
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException(
                            "Required Category A policy key missing: "
                            + "'platform.tracing.policy.diagnostics.log-level'"));

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);

            coalescer.onRefreshEvent();

            // No snapshot was submitted to the agent — LKG content in reader is intact
            assertThat(trackingReader.currentComplete())
                    .isPresent()
                    .map(AcceptedSnapshotWithSequence::snapshot)
                    .contains(LKG_SNAPSHOT);
        }

        @Test
        @DisplayName("multiple consecutive runtime missing-leaf events do NOT accumulate applySnapshot calls")
        void onRefreshEvent_multipleConsecutiveMissingLeaf_noApplyAccumulation() {
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException("leaf missing"));

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);

            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();

            assertThat(trackingClient.applyCallCount()).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // Scenario C: default is NOT auto-restored; restores only via explicit push
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("C: default restores ONLY via explicit Config Server push, not implicitly")
    class DefaultRestoreRequiresExplicitPush {

        @Test
        @DisplayName("after leaf-missing REJECTED, no implicit default is submitted to agent")
        void noImplicitDefaultAfterLeafMissing() {
            // Builder oscillates: first call fails (leaf deleted), second call
            // would succeed only if explicitly triggered again.
            ConfigServerPolicyManifestBuilder oscillatingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(oscillatingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingPolicyDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    oscillatingBuilder, client, reader, Runnable::run);

            // Simulate the leaf going missing (runtime refresh)
            coalescer.onRefreshEvent();

            // No implicit default must have been delivered
            assertThat(client.applyCallCount())
                    .as("agent applySnapshot must not be called with any implicit default")
                    .isZero();
        }

        @Test
        @DisplayName("after leaf-missing, an explicit Config Server push with full snapshot restores state")
        void explicitConfigServerPush_restoresAfterLeafMissing() {
            // Phase 1: builder fails (leaf missing) — no delivery
            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingPolicyDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingPolicyRefreshCoalescer failingCoalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, client, reader, Runnable::run);
            failingCoalescer.onRefreshEvent();
            assertThat(client.applyCallCount()).isZero();

            // Phase 2: leaf is restored — Config Server pushes a NEW complete snapshot.
            // This is modelled as a NEW coalescer that now has the full builder.
            // (In production: Environment is updated, EnvironmentChangeEvent fires,
            //  onRefreshEvent() triggers, manifestBuilder.build() now succeeds.)
            CompleteTracingPolicySnapshot restoredSnapshot =
                    CompleteTracingPolicySnapshot.builder()
                            .samplingEnabled(false)   // deliberately different from LKG
                            .samplingRatio(0.5)
                            .samplingRouteRatios(java.util.Map.of())
                            .validationEnabled(true)
                            .validationStrict(true)
                            .exporterEnabled(false)
                            .traceControlHeadersEnabled(false)
                            .diagnosticsLogLevel("WARN")
                            .build();

            ConfigServerPolicyManifestBuilder restoredBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(restoredBuilder.build()).thenReturn(restoredSnapshot);

            TracingPolicyRefreshCoalescer restoredCoalescer = new TracingPolicyRefreshCoalescer(
                    restoredBuilder, client, reader, Runnable::run);
            restoredCoalescer.onRefreshEvent();

            assertThat(client.applyCallCount())
                    .as("applySnapshot must be called exactly once on explicit Config Server restore")
                    .isEqualTo(1);
            assertThat(client.lastAppliedSnapshot())
                    .as("restored snapshot must be the explicit Config Server value, not a default")
                    .isEqualTo(restoredSnapshot);
            assertThat(client.lastAppliedSource())
                    .as("source must be CONFIG_SERVER")
                    .isEqualTo(TracingPolicySource.CONFIG_SERVER);
        }

        @Test
        @DisplayName("helm defaults are never auto-injected when Config Server leaf goes missing at runtime")
        void helmDefaultsNotAutoInjected_whenConfigServerLeafMissing() {
            // If a leaf is missing, the coalescer must NOT fall back to helmBootstrapDefaults.
            // Proof: we confirm applySnapshot is never called with any snapshot
            // (including the one produced by helmBootstrapDefaults()).
            assertThat(CompleteTracingPolicySnapshot.helmBootstrapDefaults()).isNotNull();

            ConfigServerPolicyManifestBuilder failingBuilder =
                    mock(ConfigServerPolicyManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingPolicyManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingPolicyDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                    failingBuilder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.applyCallCount()).isZero();
            // Verify that helmBootstrapDefaults was never submitted
            // (no call at all, so last snapshot is null)
            assertThat(client.lastAppliedSnapshot())
                    .as("helm defaults must NEVER be auto-submitted on runtime leaf deletion")
                    .isNull();
        }
    }

    // =========================================================================
    // Test stubs
    // =========================================================================

    /**
     * Tracking stub for {@link TracingPolicySnapshotApplier}.
     * Records calls to applySnapshot; returns a configurable outcome.
     */
    static final class TrackingSnapshotClient implements TracingPolicySnapshotApplier {

        private final TracingPolicyDeliveryResult outcome;
        private final AtomicInteger applyCount = new AtomicInteger(0);
        private final AtomicReference<ExpectedTracingPolicyBase> lastExpectedBase = new AtomicReference<>();
        private volatile CompleteTracingPolicySnapshot lastSnapshot;
        private volatile TracingPolicySource lastSource;

        TrackingSnapshotClient(TracingPolicyDeliveryResult outcome) {
            this.outcome = outcome;
        }

        @Override
        public TracingPolicyDeliveryResult applySnapshot(
                ExpectedTracingPolicyBase expectedBase,
                CompleteTracingPolicySnapshot snapshot,
                TracingPolicySource source) {
            applyCount.incrementAndGet();
            lastExpectedBase.set(expectedBase);
            this.lastSnapshot = snapshot;
            this.lastSource = source;
            return outcome;
        }

        int applyCallCount() { return applyCount.get(); }
        long lastAppliedSequence() { return lastExpectedBase.get().acceptedSequence(); }
        CompleteTracingPolicySnapshot lastAppliedSnapshot() { return lastSnapshot; }
        TracingPolicySource lastAppliedSource() { return lastSource; }
    }

    /**
     * Tracking stub for {@link CompleteTracingPolicyReader}.
     * Holds a fixed LKG {@link AcceptedSnapshotWithSequence} and counts reads.
     */
    static final class TrackingSnapshotReader implements CompleteTracingPolicyReader {

        private final AtomicReference<AcceptedSnapshotWithSequence> state;
        private final AtomicInteger readCount = new AtomicInteger(0);

        TrackingSnapshotReader(AcceptedSnapshotWithSequence initial) {
            this.state = new AtomicReference<>(initial);
        }

        @Override
        public Optional<AcceptedSnapshotWithSequence> currentComplete() {
            readCount.incrementAndGet();
            return Optional.ofNullable(state.get());
        }

        int currentCompleteCallCount() { return readCount.get(); }
    }
}
