package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

@DisplayName("Config Server через JMX-клиент снимков")
class ProductionFlowTest {

    private static final CompleteTracingSettings INITIAL_SNAPSHOT =
            CompleteTracingSettings.builder()
                    .samplingEnabled(true)
                    .samplingRatio(0.8)
                    .samplingRouteRatios(Map.of("/api/health", 0.0, "/api/orders", 1.0))
                    .validationLevel("WARNING")
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("INFO")
                    .build();

    private static final CompleteTracingSettings DELTA_SNAPSHOT =
            CompleteTracingSettings.builder()
                    .samplingEnabled(true)
                    .samplingRatio(0.5)
                    .samplingRouteRatios(Map.of("/api/orders", 0.9))
                    .validationLevel("WARNING")
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("INFO")
                    .build();

    private static final CompleteTracingSettings RETRY_SNAPSHOT =
            CompleteTracingSettings.builder()
                    .samplingEnabled(true)
                    .samplingRatio(0.5)
                    .samplingRouteRatios(Map.of("/api/orders", 0.9))
                    .validationLevel("WARNING")
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("WARN")
                    .build();

    private static final long BASE_SEQUENCE = 0L;
    private static final String AGENT_INSTANCE_ID = "66f8525d-95e6-4cf8-a2ca-d384e6fd7b3c";

    @Nested
    @DisplayName("happy path: initial Config Server delivery")
    class HappyPath {

        private RecordingSnapshotClient client;
        private RecordingSnapshotReader reader;

        @BeforeEach
        void setUp() {
            client = new RecordingSnapshotClient();
            reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));
        }

        @Test
        @DisplayName("deliverInitial calls applySnapshot exactly once with all 7 leaves")
        void deliverInitial_callsApplySnapshotOnce() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.deliverInitial();

            assertThat(client.totalApplyCalls())
                    .as("applySnapshot must be called exactly once for initial delivery")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("applied snapshot contains all 7 Category A leaves unchanged")
        void appliedSnapshot_containsAll8Leaves() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.deliverInitial();

            CompleteTracingSettings applied = client.lastAppliedSnapshot();
            assertThat(applied).isNotNull();
            assertThat(applied.samplingEnabled()).isTrue();
            assertThat(applied.samplingRatio()).isEqualTo(0.8);
            assertThat(applied.samplingRouteRatios())
                    .containsEntry("/api/health", 0.0)
                    .containsEntry("/api/orders", 1.0);
            assertThat(applied.validationLevel()).isEqualTo("WARNING");
            assertThat(applied.exporterEnabled()).isTrue();
            assertThat(applied.traceControlHeadersEnabled()).isTrue();
            assertThat(applied.diagnosticsLogLevel()).isEqualTo("INFO");
        }

        @Test
        @DisplayName("source tag on initial delivery is CONFIG_SERVER")
        void sourceTag_isConfigServer() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.deliverInitial();

            assertThat(client.lastAppliedSource())
                    .as("source tag MUST be CONFIG_SERVER for initial delivery")
                    .isEqualTo(SettingsWriter.CONFIG_SERVER);
        }

        @Test
        @DisplayName("expectedSequence on initial call matches reader's current sequence")
        void expectedSequence_matchesReaderCurrentSequence() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.deliverInitial();

            assertThat(client.lastAppliedExpectedSequence())
                    .as("expectedSequence passed to writer must equal current acceptedSequence from reader")
                    .isEqualTo(BASE_SEQUENCE);
        }
    }

    @Nested
    @DisplayName("runtime delta: second refresh with changed leaf → APPLIED")
    class RuntimeDelta {

        private RecordingSnapshotClient client;
        private RecordingSnapshotReader reader;

        @BeforeEach
        void setUp() {
            client = new RecordingSnapshotClient();
            reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));
        }

        @Test
        @DisplayName("onRefreshEvent calls applySnapshot with updated snapshot")
        void onRefreshEvent_callsApplyWithUpdatedSnapshot() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(DELTA_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls()).isEqualTo(1);
            assertThat(client.lastAppliedSnapshot()).isEqualTo(DELTA_SNAPSHOT);
        }

        @Test
        @DisplayName("updated samplingRatio is reflected in submitted snapshot")
        void updatedSamplingRatio_inAppliedSnapshot() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(DELTA_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSnapshot().samplingRatio())
                    .as("samplingRatio must reflect the Config Server delta value")
                    .isEqualTo(0.5);
        }

        @Test
        @DisplayName("route map delta (one route removed) is reflected in snapshot")
        void routeMapDelta_reflected() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(DELTA_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            Map<String, Double> routes = client.lastAppliedSnapshot().samplingRouteRatios();
            assertThat(routes)
                    .as("/api/health route must be absent after delta removal")
                    .doesNotContainKey("/api/health");
            assertThat(routes).containsEntry("/api/orders", 0.9);
        }

        @Test
        @DisplayName("source tag on runtime delta delivery is CONFIG_SERVER")
        void sourceTag_isConfigServer() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(DELTA_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSource()).isEqualTo(SettingsWriter.CONFIG_SERVER);
        }

        @Test
        @DisplayName("two sequential refresh events each call applySnapshot once")
        void twoSequentialRefreshEvents_eachCallApplyOnce() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT, DELTA_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);

            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("each completed refresh cycle must call applySnapshot once (total = 2)")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("STALE conflict: one bounded retry → APPLIED")
    class StaleWithRetry {

        @Test
        @DisplayName("STALE → one retry → APPLIED; total applySnapshot calls = 2")
        void stale_then_retry_appliesOnce() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("STALE must trigger exactly one retry: total calls = 2")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("final applied snapshot after retry is the rebuilt snapshot")
        void finalAppliedSnapshot_isRebuiltAfterStale() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT, RETRY_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSnapshot())
                    .as("final snapshot after retry must be the freshly rebuilt one")
                    .isEqualTo(RETRY_SNAPSHOT);
        }

        @Test
        @DisplayName("source tag on retry attempt is CONFIG_SERVER")
        void sourceTag_onRetry_isConfigServer() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSource())
                    .as("source tag on retry must remain CONFIG_SERVER")
                    .isEqualTo(SettingsWriter.CONFIG_SERVER);
            assertThat(client.allAppliedSources())
                    .as("every apply attempt must be CONFIG_SERVER")
                    .containsOnly(SettingsWriter.CONFIG_SERVER);
        }

        @Test
        @DisplayName("STALE + retry-STALE = no further retry; total calls = 2 (bounded)")
        void stale_retryAlsoStale_noFurtherRetry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("bounded: after STALE+STALE coalescer must NOT retry a third time")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("APPLIED on first attempt does NOT trigger any retry")
        void applied_on_first_attempt_noRetry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("APPLIED on first attempt must produce exactly 1 call, no retry")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("REJECTED does NOT trigger retry (retry is STALE-only)")
        void rejected_does_not_trigger_retry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.setNextOutcome(TracingSettingsDeliveryResult.REJECTED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, recordingSettingsWriter(client, reader), Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("REJECTED must not trigger retry; total calls = 1")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("FAILED does NOT trigger retry (retry is STALE-only)")
        void failed_does_not_trigger_retry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.setNextOutcome(TracingSettingsDeliveryResult.FAILED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    AcceptedSettingsTestFixtures.configServerAccepted(BASE_SEQUENCE, AGENT_INSTANCE_ID, INITIAL_SNAPSHOT)
            );

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(INITIAL_SNAPSHOT);

            TracingSettingsRefreshCoalescer coalescer = new TracingSettingsRefreshCoalescer(
                    builder,
                    recordingSettingsWriter(client, reader),
                    Runnable::run
            );
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("FAILED must not trigger retry; total calls = 1")
                    .isEqualTo(1);
        }
    }

    static final class RecordingSnapshotClient implements RecordingSettingsWriter.ApplyBehavior {

        private final AtomicInteger totalCalls = new AtomicInteger(0);
        private final AtomicReference<SettingsRevision> lastExpectedBase = new AtomicReference<>();
        private final Queue<SettingsWriter> appliedSources = new ConcurrentLinkedQueue<>();
        private volatile CompleteTracingSettings lastSnapshot;
        private volatile SettingsWriter lastSource;

        private final Queue<TracingSettingsDeliveryResult> outcomeQueue = new ConcurrentLinkedQueue<>();
        private volatile TracingSettingsDeliveryResult defaultOutcome = TracingSettingsDeliveryResult.APPLIED;

        void setNextOutcome(TracingSettingsDeliveryResult outcome) {
            outcomeQueue.clear();
            defaultOutcome = outcome;
        }

        void enqueueOutcome(TracingSettingsDeliveryResult outcome) {
            outcomeQueue.add(outcome);
        }

        @Override
        public SettingsDelivery applySnapshot(SettingsRevision expectedBase,
                                              CompleteTracingSettings snapshot,
                                              SettingsWriter writer) {
            totalCalls.incrementAndGet();
            lastExpectedBase.set(expectedBase);
            this.lastSnapshot = snapshot;
            this.lastSource = writer;
            appliedSources.add(writer);
            TracingSettingsDeliveryResult next = outcomeQueue.poll();
            TracingSettingsDeliveryResult outcome = next != null ? next : defaultOutcome;
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

        int totalApplyCalls() {
            return totalCalls.get();
        }

        long lastAppliedExpectedSequence() {
            return lastExpectedBase.get().acceptedSequence();
        }

        CompleteTracingSettings lastAppliedSnapshot() {
            return lastSnapshot;
        }

        SettingsWriter lastAppliedSource() {
            return lastSource;
        }

        List<SettingsWriter> allAppliedSources() {
            return List.copyOf(appliedSources);
        }
    }

    static final class RecordingSnapshotReader implements Supplier<Optional<AcceptedSettings>> {

        private final AtomicReference<AcceptedSettings> state;

        RecordingSnapshotReader(AcceptedSettings initial) {
            this.state = new AtomicReference<>(initial);
        }

        @Override
        public Optional<AcceptedSettings> get() {
            return Optional.ofNullable(state.get());
        }
    }
}
