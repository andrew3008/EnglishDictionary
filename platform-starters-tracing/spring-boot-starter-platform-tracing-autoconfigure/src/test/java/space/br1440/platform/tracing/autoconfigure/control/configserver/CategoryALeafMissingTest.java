package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsManifestException;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

@DisplayName("Category A leaf missing/deleted: readiness BLOCKED & runtime REJECTED")
class CategoryALeafMissingTest {

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
    private static final String LKG_AGENT_INSTANCE_ID = "d7468019-f52a-44a9-b3cd-3654c13ebc4d";

    private static AcceptedSettings lkgAcceptedState() {
        return AcceptedSettingsTestFixtures.configServerAccepted(LKG_SEQUENCE, LKG_AGENT_INSTANCE_ID, LKG_SNAPSHOT);
    }

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

            RecordingSettingsWriter.ApplyBehavior client = mock(RecordingSettingsWriter.ApplyBehavior.class);
            @SuppressWarnings("unchecked")
            Supplier<Optional<AcceptedSettings>> reader = mock(Supplier.class);

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    failingBuilder, recordingSettingsWriter(client, reader));

            assertThatThrownBy(synchronizer::deliverInitial)
                    .isInstanceOf(TracingSettingsManifestException.class)
                    .hasMessageContaining("platform.tracing.policy.sampling.enabled");
        }

        @Test
        @DisplayName("deliverInitial never reaches applySnapshot when leaf is missing")
        void deliverInitial_neverCallsApplySnapshot_whenLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder = mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("diagnosticsLogLevel not set"));

            RecordingSettingsWriter.ApplyBehavior client = mock(RecordingSettingsWriter.ApplyBehavior.class);
            @SuppressWarnings("unchecked")
            Supplier<Optional<AcceptedSettings>> reader = mock(Supplier.class);

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    failingBuilder, recordingSettingsWriter(client, reader));

            try {
                synchronizer.deliverInitial();
            } catch (TracingSettingsManifestException ignored) {
                // ожидаемое исключение
            }

            verify(client, never()).applySnapshot(any(), any(), any());
        }

        @Test
        @DisplayName("deliverInitial throws for EACH of the 6 mandatory non-route-ratio leaves")
        void deliverInitial_throwsForEachMandatoryLeaf() {
            String[] mandatoryLeaves = {
                    "platform.tracing.policy.sampling.enabled",
                    "platform.tracing.policy.sampling.ratio",
                    "platform.tracing.policy.validation.level",
                    "platform.tracing.policy.exporter.enabled",
                    "platform.tracing.policy.propagation.trace-control-headers.enabled",
                    "platform.tracing.policy.diagnostics.log-level"
            };

            for (String leaf : mandatoryLeaves) {
                ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
                when(builder.build())
                        .thenThrow(new TracingSettingsManifestException(
                                "Required Category A policy key missing: '" + leaf + "'"));

                RecordingSettingsWriter.ApplyBehavior client = mock(RecordingSettingsWriter.ApplyBehavior.class);
                @SuppressWarnings("unchecked")
                Supplier<Optional<AcceptedSettings>> reader = mock(Supplier.class);

                ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                        builder, recordingSettingsWriter(client, reader));

                assertThatThrownBy(synchronizer::deliverInitial)
                        .as("missing leaf: %s", leaf)
                        .isInstanceOf(TracingSettingsManifestException.class)
                        .hasMessageContaining(leaf);

                verify(client, never()).applySnapshot(any(), any(), any());
            }
        }
    }

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

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    failingBuilder, recordingSettingsWriter(trackingClient, trackingReader));

            refresh(synchronizer);

            assertThat(trackingClient.applyCallCount()).isZero();
        }

        @Test
        @DisplayName("runtime missing leaf does not apply and leaves the last applied sequence unchanged")
        void onRefreshEvent_lastAppliedSequence_isUnchangedWhenLeafMissing() {
            // given a successful delivery
            ConfigServerSettingsManifestBuilder goodBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(goodBuilder.build()).thenReturn(LKG_SNAPSHOT);

            trackingClient = new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            trackingReader = new TrackingSnapshotReader(lkgAcceptedState());

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    goodBuilder, recordingSettingsWriter(trackingClient, trackingReader));
            refresh(synchronizer);

            long sequenceAfterGoodApply = trackingClient.lastAppliedSequence();

            // Now: leaf disappears
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException(
                            "Required Category A policy key missing: "
                                    + "'platform.tracing.policy.sampling.ratio'"));

            ConfigServerTracingSettingsSynchronizer failingSynchronizer =
                    new ConfigServerTracingSettingsSynchronizer(
                            failingBuilder, recordingSettingsWriter(trackingClient, trackingReader));
            int applyCountBefore = trackingClient.applyCallCount();

            refresh(failingSynchronizer);

            assertThat(trackingClient.applyCallCount())
                    .as("applySnapshot must NOT be called when leaf is missing")
                    .isEqualTo(applyCountBefore);

            assertThat(trackingClient.lastAppliedSequence())
                    .as("acceptedSequence must remain unchanged")
                    .isEqualTo(sequenceAfterGoodApply);
        }

        @Test
        @DisplayName("agentInstanceId returned by reader is unchanged after runtime missing leaf")
        void onRefreshEvent_agentInstanceId_isUnchangedWhenLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException(
                            "Required Category A policy key missing: "
                                    + "'platform.tracing.policy.validation.level'"));

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    failingBuilder, recordingSettingsWriter(trackingClient, trackingReader));

            refresh(synchronizer);

            assertThat(trackingReader.currentCallCount())
                    .as("reader.current() must not be called when build() fails")
                    .isZero();

            assertThat(trackingReader.get())
                    .isPresent()
                    .map(accepted -> accepted.revision().agentInstanceId())
                    .contains(LKG_AGENT_INSTANCE_ID);
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

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    failingBuilder, recordingSettingsWriter(trackingClient, trackingReader));

            refresh(synchronizer);

            assertThat(trackingReader.get())
                    .isPresent()
                    .map(AcceptedSettings::policy)
                    .contains(LKG_SNAPSHOT);
        }

        @Test
        @DisplayName("multiple consecutive runtime missing-leaf events do NOT accumulate applySnapshot calls")
        void onRefreshEvent_multipleConsecutiveMissingLeaf_noApplyAccumulation() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("leaf missing"));

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    failingBuilder, recordingSettingsWriter(trackingClient, trackingReader));

            refresh(synchronizer);
            refresh(synchronizer);
            refresh(synchronizer);

            assertThat(trackingClient.applyCallCount()).isZero();
        }
    }

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

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    oscillatingBuilder, recordingSettingsWriter(client, reader));

            refresh(synchronizer);

            assertThat(client.applyCallCount())
                    .as("agent applySnapshot must not be called with any implicit default")
                    .isZero();
        }

        @Test
        @DisplayName("after leaf-missing, an explicit Config Server push with full snapshot restores state")
        void explicitConfigServerPush_restoresAfterLeafMissing() {
            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            ConfigServerTracingSettingsSynchronizer failingSynchronizer =
                    new ConfigServerTracingSettingsSynchronizer(
                            failingBuilder, recordingSettingsWriter(client, reader));
            refresh(failingSynchronizer);
            assertThat(client.applyCallCount()).isZero();

            CompleteTracingSettings restoredSnapshot =
                    CompleteTracingSettings.builder()
                            .samplingEnabled(false)
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

            ConfigServerTracingSettingsSynchronizer restoredSynchronizer =
                    new ConfigServerTracingSettingsSynchronizer(
                            restoredBuilder, recordingSettingsWriter(client, reader));
            refresh(restoredSynchronizer);

            assertThat(client.applyCallCount())
                    .as("applySnapshot must be called exactly once on explicit Config Server restore")
                    .isEqualTo(1);
            assertThat(client.lastAppliedSnapshot())
                    .as("restored snapshot must be the explicit Config Server value, not a default")
                    .isEqualTo(restoredSnapshot);
            assertThat(client.lastAppliedSource())
                    .as("source must be CONFIG_SERVER")
                    .isEqualTo(SettingsWriter.CONFIG_SERVER);
        }

        @Test
        @DisplayName("helm defaults are never auto-injected when Config Server leaf goes missing at runtime")
        void helmDefaultsNotAutoInjected_whenConfigServerLeafMissing() {
            assertThat(CompleteTracingSettings.helmBootstrapDefaults()).isNotNull();

            ConfigServerSettingsManifestBuilder failingBuilder =
                    mock(ConfigServerSettingsManifestBuilder.class);
            when(failingBuilder.build())
                    .thenThrow(new TracingSettingsManifestException("leaf missing"));

            TrackingSnapshotClient client =
                    new TrackingSnapshotClient(TracingSettingsDeliveryResult.APPLIED);
            TrackingSnapshotReader reader = new TrackingSnapshotReader(lkgAcceptedState());

            ConfigServerTracingSettingsSynchronizer synchronizer = new ConfigServerTracingSettingsSynchronizer(
                    failingBuilder, recordingSettingsWriter(client, reader));
            refresh(synchronizer);

            assertThat(client.applyCallCount()).isZero();
            assertThat(client.lastAppliedSnapshot())
                    .as("helm defaults must NEVER be auto-submitted on runtime leaf deletion")
                    .isNull();
        }
    }

    private static void refresh(ConfigServerTracingSettingsSynchronizer synchronizer) {
        ConfigServerTracingSettingsAdapter adapter = new ConfigServerTracingSettingsAdapter(synchronizer);
        adapter.onEnvironmentChange(new EnvironmentChangeEvent(
                Set.of("platform.tracing.policy.sampling.ratio")
        ));
    }

    static final class TrackingSnapshotClient implements RecordingSettingsWriter.ApplyBehavior {

        private final TracingSettingsDeliveryResult outcome;
        private final AtomicInteger applyCount = new AtomicInteger(0);
        private final AtomicReference<SettingsRevision> lastExpectedBase = new AtomicReference<>();
        private volatile CompleteTracingSettings lastSnapshot;
        private volatile SettingsWriter lastSource;

        TrackingSnapshotClient(TracingSettingsDeliveryResult outcome) {
            this.outcome = outcome;
        }

        @Override
        public SettingsDelivery applySnapshot(
                SettingsRevision expectedBase,
                CompleteTracingSettings snapshot,
                SettingsWriter writer) {
            applyCount.incrementAndGet();
            lastExpectedBase.set(expectedBase);
            this.lastSnapshot = snapshot;
            this.lastSource = writer;
            return delivery(outcome);
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

        private static SettingsDelivery delivery(TracingSettingsDeliveryResult outcome) {
            String messageCode = switch (outcome) {
                case APPLIED, UNCHANGED -> "NONE";
                case REJECTED -> "INVALID_VALUE";
                case STALE -> "ACCEPTED_SEQUENCE_MISMATCH";
                case FAILED -> "INTERNAL_FAILURE";
            };

            return new SettingsDelivery(
                    "00000000-0000-0000-0000-000000000001",
                    outcome,
                    messageCode
            );
        }

        SettingsWriter lastAppliedSource() {
            return lastSource;
        }
    }

    static final class TrackingSnapshotReader implements Supplier<Optional<AcceptedSettings>> {

        private final AtomicReference<AcceptedSettings> state;
        private final AtomicInteger readCount = new AtomicInteger(0);

        TrackingSnapshotReader(AcceptedSettings initial) {
            this.state = new AtomicReference<>(initial);
        }

        @Override
        public Optional<AcceptedSettings> get() {
            readCount.incrementAndGet();
            return Optional.ofNullable(state.get());
        }

        int currentCallCount() {
            return readCount.get();
        }
    }
}
