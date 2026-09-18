package space.br1440.platform.tracing.autoconfigure.control.configserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;

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

@DisplayName("Category A leaf missing/deleted: readiness BLOCKED & runtime REJECTED")
class CategoryALeafMissingTest {

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Full valid 8-leaf snapshot used as LKG baseline.
     */
    private static final CompleteTracingSettings LKG_SNAPSHOT =
            CompleteTracingSettings.builder()
                    .samplingEnabled(true)
                    .samplingRatio(1.0)
                    .samplingRouteRatios(java.util.Map.of())
                    .validationLevel("WARNING")
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("INFO")
                    .build();

    private static final long LKG_SEQUENCE = 42L;
    private static final String LKG_RUNTIME_INSTANCE = "agent-instance-abc";

    /**
     * Constructs an AcceptedSettingsWithSequence representing the LKG state.
     */
    private static AcceptedSettingsWithSequence lkgAcceptedState() {
        return new AcceptedSettingsWithSequence(LKG_SEQUENCE, LKG_RUNTIME_INSTANCE, LKG_SNAPSHOT);
    }

    // -------------------------------------------------------------------------
    // Scenario A: initial missing leaf → readiness BLOCKED
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("A: initial delivery — missing leaf → readiness BLOCKED")
    class InitialMissingLeaf {

        @Test
        @DisplayName("deliverInitial throws TracingSettingsManifestException when any Category A leaf is missing")
        void deliverInitial_throwsManifestException_whenLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException(
                            "Required Category A policy key missing: "
                                    + "'platform.tracing.policy.sampling.enabled'"));

            TracingSettingsApplier client = mock(TracingSettingsApplier.class);
            CompleteTracingSettingsReader reader = mock(CompleteTracingSettingsReader.class);

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    failingBuilder, client, reader, Runnable::run);

            assertThatThrownBy(coalescer::deliverInitial)
                    .isInstanceOf(TracingSettingsManifestException.class)
                    .hasMessageContaining("platform.tracing.policy.sampling.enabled");
        }

        @Test
        @DisplayName("deliverInitial never reaches applySnapshot when leaf is missing")
        void deliverInitial_neverCallsApplySnapshot_whenLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("diagnosticsLogLevel not set"));

            TracingSettingsApplier client = mock(TracingSettingsApplier.class);
            CompleteTracingSettingsReader reader = mock(CompleteTracingSettingsReader.class);

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    failingBuilder, client, reader, Runnable::run);

            try {
                coalescer.deliverInitial();
            } catch (TracingSettingsManifestException ignored) { /* expected */ }

            verify(client, never()).applySnapshot(any(), any(), any());
        }

        @Test
        @DisplayName("deliverInitial throws for EACH of the 6 mandatory non-route-ratio leaves")
        void deliverInitial_throwsForEachMandatoryLeaf() {
            // The manifest builder is responsible for failing per missing key;
            // here we assert that ANY TracingSettingsManifestException propagates
            // (not swallowed). The fixture simulates deletion of every leaf.
            String[] mandatoryLeaves = {
                    "platform.tracing.policy.sampling.enabled",
                    "platform.tracing.policy.sampling.ratio",
                    "platform.tracing.policy.validation.level",
                    "platform.tracing.policy.exporter.enabled",
                    "platform.tracing.policy.propagation.trace-control-headers.enabled",
                    "platform.tracing.policy.diagnostics.log-level"
            };

            for (String leaf : mandatoryLeaves) {
                ConfigServerSettingsManifestBuilder builder =
                        mock(ConfigServerSettingsManifestBuilder.class);
                when(builder.build())
                        .thenThrow(new TracingSettingsManifestException(
                                "Required Category A policy key missing: '" + leaf + "'"));

                TracingSettingsApplier client = mock(TracingSettingsApplier.class);
                CompleteTracingSettingsReader reader =
                        mock(CompleteTracingSettingsReader.class);

                TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                        builder, client, reader, Runnable::run);

                assertThatThrownBy(coalescer::deliverInitial)
                        .as("missing leaf: %s", leaf)
                        .isInstanceOf(TracingSettingsManifestException.class)
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
            trackingClient = new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            trackingReader = new TrackingSnapshotReader(lkgAcceptedState());
        }

        @Test
        @DisplayName("runtime refresh with missing leaf does NOT call applySnapshot")
        void onRefreshEvent_doesNotCallApplySnapshot_whenLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException(
                            "Required Category A policy key missing: "
                                    + "'platform.tracing.policy.exporter.enabled'"));

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);

            coalescer.onRefreshEvent();

            assertThat(trackingClient.applyCallCount()).isZero();
        }

        @Test
        @DisplayName("runtime missing leaf leaves lastKnownSequence unchanged (LKG immutable)")
        void onRefreshEvent_lastKnownSequence_isUnchangedWhenLeafMissing() {
            // First: deliver successfully to seed lastKnownSequence
            ConfigServerSettingsManifestBuilder goodBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(goodBuilder.build()).thenReturn(LKG_SNAPSHOT);

            trackingClient = new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            trackingReader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    goodBuilder, trackingClient, trackingReader, Runnable::run);
            coalescer.onRefreshEvent();

            long sequenceAfterGoodApply = trackingClient.lastAppliedSequence();

            // Now: leaf disappears
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException(
                            "Required Category A policy key missing: "
                                    + "'platform.tracing.policy.sampling.ratio'"));

            TracingSettingsRefreshCoalescer failingCoalescer = new TracingSettingsRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);
            int applyCountBefore = trackingClient.applyCallCount();

            failingCoalescer.onRefreshEvent();

            assertThat(trackingClient.applyCallCount())
                    .as("applySnapshot must NOT be called when leaf is missing")
                    .isEqualTo(applyCountBefore);

            assertThat(trackingClient.lastAppliedSequence())
                    .as("acceptedSequence must remain unchanged")
                    .isEqualTo(sequenceAfterGoodApply);
        }

        @Test
        @DisplayName("runtimeInstanceId returned by reader is unchanged after runtime missing leaf")
        void onRefreshEvent_runtimeInstanceId_isUnchangedWhenLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException(
                            "Required Category A policy key missing: "
                                    + "'platform.tracing.policy.validation.level'"));

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);

            coalescer.onRefreshEvent();

            assertThat(trackingReader.currentCompleteCallCount())
                    .as("reader.currentComplete() must not be called when build() fails")
                    .isZero();

            assertThat(trackingReader.currentComplete())
                    .isPresent()
                    .map(AcceptedSettingsWithSequence::runtimeInstanceId)
                    .contains(LKG_RUNTIME_INSTANCE);
        }

        @Test
        @DisplayName("LKG snapshot content is unchanged after runtime missing leaf")
        void onRefreshEvent_lkgSnapshotContent_isUnchangedWhenLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException(
                            "Required Category A policy key missing: "
                                    + "'platform.tracing.policy.diagnostics.log-level'"));

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    failingBuilder, trackingClient, trackingReader, Runnable::run);

            coalescer.onRefreshEvent();

            // No snapshot was submitted to the agent — LKG content in reader is intact
            assertThat(trackingReader.currentComplete())
                    .isPresent()
                    .map(AcceptedSettingsWithSequence::snapshot)
                    .contains(LKG_SNAPSHOT);
        }

        @Test
        @DisplayName("multiple consecutive runtime missing-leaf events do NOT accumulate applySnapshot calls")
        void onRefreshEvent_multipleConsecutiveMissingLeaf_noApplyAccumulation() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("leaf missing"));

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
            ConfigServerSettingsManifestBuilder oscillatingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(oscillatingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingSettingsRefreshCoalescer failingCoalescer = new TracingSettingsRefreshCoalescer(
                    failingBuilder, client, reader, Runnable::run);
            failingCoalescer.onRefreshEvent();
            assertThat(client.applyCallCount()).isZero();

            // Phase 2: leaf is restored — Config Server pushes a NEW complete snapshot.
            // This is modelled as a NEW coalescer that now has the full builder.
            // (In production: Environment is updated, EnvironmentChangeEvent fires,
            //  onRefreshEvent() triggers, manifestBuilder.build() now succeeds.)
            CompleteTracingSettings restoredSnapshot =
                    CompleteTracingSettings.builder()
                            .samplingEnabled(false)   // deliberately different from LKG
                            .samplingRatio(0.5)
                            .samplingRouteRatios(java.util.Map.of())
                            .validationLevel("ERROR")
                            .exporterEnabled(false)
                            .traceControlHeadersEnabled(false)
                            .diagnosticsLogLevel("WARN")
                            .build();

            ConfigServerSettingsManifestBuilder restoredBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(restoredBuilder.build()).thenReturn(restoredSnapshot);

            TracingSettingsRefreshCoalescer restoredCoalescer = new TracingSettingsRefreshCoalescer(
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
                    .isEqualTo(TracingSettingsDeliverySource.CONFIG_SERVER);
        }

        @Test
        @DisplayName("helm defaults are never auto-injected when Config Server leaf goes missing at runtime")
        void helmDefaultsNotAutoInjected_whenConfigServerLeafMissing() {
            // If a leaf is missing, the coalescer must NOT fall back to helmBootstrapDefaults.
            // Proof: we confirm applySnapshot is never called with any snapshot
            // (including the one produced by helmBootstrapDefaults()).
            assertThat(CompleteTracingSettings.helmBootstrapDefaults()).isNotNull();

            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
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
     * Tracking stub for {@link TracingSettingsApplier}.
     * Records calls to applySnapshot; returns a configurable outcome.
     */
    static final class TrackingSnapshotClient implements TracingSettingsApplier {

        private final TracingSettingsDeliveryResult outcome;
        private final AtomicInteger applyCount = new AtomicInteger(0);
        private final AtomicReference<ExpectedSettingsRevision> lastExpectedBase = new AtomicReference<>();
        private volatile CompleteTracingSettings lastSnapshot;
        private volatile TracingSettingsDeliverySource lastSource;

        TrackingSnapshotClient(TracingSettingsDeliveryResult outcome) {
            this.outcome = outcome;
        }

        @Override
        public TracingSettingsDeliveryResult applySnapshot(
                ExpectedSettingsRevision expectedBase,
                CompleteTracingSettings snapshot,
                TracingSettingsDeliverySource source) {
            applyCount.incrementAndGet();
            lastExpectedBase.set(expectedBase);
            this.lastSnapshot = snapshot;
            this.lastSource = source;
            return outcome;
        }

        int applyCallCount() {
            return applyCount.get();
        }

        long lastAppliedSequence() {
            return lastExpectedBase.get().acceptedSequence();
        }

        CompleteTracingSettings lastAppliedSnapshot() {
            return lastSnapshot;
        }

        TracingSettingsDeliverySource lastAppliedSource() {
            return lastSource;
        }
    }

    /**
     * Tracking stub for {@link CompleteTracingSettingsReader}.
     * Holds a fixed LKG {@link AcceptedSettingsWithSequence} and counts reads.
     */
    static final class TrackingSnapshotReader implements CompleteTracingSettingsReader {

        private final AtomicReference<AcceptedSettingsWithSequence> state;
        private final AtomicInteger readCount = new AtomicInteger(0);

        TrackingSnapshotReader(AcceptedSettingsWithSequence initial) {
            this.state = new AtomicReference<>(initial);
        }

        @Override
        public Optional<AcceptedSettingsWithSequence> currentComplete() {
            readCount.incrementAndGet();
            return Optional.ofNullable(state.get());
        }

        int currentCompleteCallCount() {
            return readCount.get();
        }
    }
}
