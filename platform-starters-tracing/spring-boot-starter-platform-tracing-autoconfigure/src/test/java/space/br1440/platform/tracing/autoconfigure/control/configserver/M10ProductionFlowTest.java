package space.br1440.platform.tracing.autoconfigure.control.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

@DisplayName("M10/M10c/M10d: Config Server через JMX-клиент снимков")
class M10ProductionFlowTest {

    // =========================================================================
    // Canonical 8-leaf snapshots
    // =========================================================================

    /** M10 initial complete snapshot — all 8 leaves present. */
    private static final CompleteTracingSettings M10_INITIAL =
            CompleteTracingSettings.builder()
                    .samplingEnabled(true)
                    .samplingRatio(0.8)
                    .samplingRouteRatios(Map.of("/api/health", 0.0, "/api/orders", 1.0))
                    .validationEnabled(true)
                    .validationStrict(false)
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("INFO")
                    .build();

    /** M10c delta snapshot — samplingRatio changed to 0.5, one route removed. */
    private static final CompleteTracingSettings M10C_DELTA =
            CompleteTracingSettings.builder()
                    .samplingEnabled(true)
                    .samplingRatio(0.5)
                    .samplingRouteRatios(Map.of("/api/orders", 0.9))
                    .validationEnabled(true)
                    .validationStrict(false)
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("INFO")
                    .build();

    /** M10d post-retry snapshot — rebuilt after STALE. */
    private static final CompleteTracingSettings M10D_RETRY =
            CompleteTracingSettings.builder()
                    .samplingEnabled(true)
                    .samplingRatio(0.5)
                    .samplingRouteRatios(Map.of("/api/orders", 0.9))
                    .validationEnabled(true)
                    .validationStrict(false)
                    .exporterEnabled(true)
                    .traceControlHeadersEnabled(true)
                    .diagnosticsLogLevel("WARN")
                    .build();

    private static final long BASE_SEQUENCE = 0L;
    private static final String RUNTIME_ID = "agent-runtime-m10-test";

    // =========================================================================
    // M10 — Happy path: initial Config Server delivery → APPLIED
    // =========================================================================

    @Nested
    @DisplayName("M10 – happy path: initial Config Server delivery")
    class M10HappyPath {

        private RecordingSnapshotClient client;
        private RecordingSnapshotReader reader;

        @BeforeEach
        void setUp() {
            client = new RecordingSnapshotClient();
            reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));
        }

        @Test
        @DisplayName("M10-1: deliverInitial calls applySnapshot exactly once with all 8 leaves")
        void m10_deliverInitial_callsApplySnapshotOnce() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.deliverInitial();

            assertThat(client.totalApplyCalls())
                    .as("applySnapshot must be called exactly once for M10 initial delivery")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("M10-2: applied snapshot contains all 8 Category A leaves unchanged")
        void m10_appliedSnapshot_containsAll8Leaves() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.deliverInitial();

            CompleteTracingSettings applied = client.lastAppliedSnapshot();
            assertThat(applied).isNotNull();
            assertThat(applied.samplingEnabled()).isTrue();
            assertThat(applied.samplingRatio()).isEqualTo(0.8);
            assertThat(applied.samplingRouteRatios())
                    .containsEntry("/api/health", 0.0)
                    .containsEntry("/api/orders", 1.0);
            assertThat(applied.validationEnabled()).isTrue();
            assertThat(applied.validationStrict()).isFalse();
            assertThat(applied.exporterEnabled()).isTrue();
            assertThat(applied.traceControlHeadersEnabled()).isTrue();
            assertThat(applied.diagnosticsLogLevel()).isEqualTo("INFO");
        }

        @Test
        @DisplayName("M10-3: source tag on initial delivery is CONFIG_SERVER")
        void m10_sourceTag_isConfigServer() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.deliverInitial();

            assertThat(client.lastAppliedSource())
                    .as("source tag MUST be CONFIG_SERVER for initial delivery")
                    .isEqualTo(TracingSettingsDeliverySource.CONFIG_SERVER);
        }

        @Test
        @DisplayName("M10-4: expectedSequence on initial call matches reader's current sequence")
        void m10_expectedSequence_matchesReaderCurrentSequence() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.deliverInitial();

            assertThat(client.lastAppliedExpectedSequence())
                    .as("expectedSequence passed to writer must equal current acceptedSequence from reader")
                    .isEqualTo(BASE_SEQUENCE);
        }
    }

    // =========================================================================
    // M10c — Runtime delta: second refresh with one leaf changed → APPLIED
    // =========================================================================

    @Nested
    @DisplayName("M10c – runtime delta: second refresh with changed leaf → APPLIED")
    class M10cRuntimeDelta {

        private RecordingSnapshotClient client;
        private RecordingSnapshotReader reader;

        @BeforeEach
        void setUp() {
            client = new RecordingSnapshotClient();
            reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));
        }

        @Test
        @DisplayName("M10c-1: onRefreshEvent calls applySnapshot with updated snapshot")
        void m10c_onRefreshEvent_callsApplyWithUpdatedSnapshot() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10C_DELTA);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls()).isEqualTo(1);
            assertThat(client.lastAppliedSnapshot()).isEqualTo(M10C_DELTA);
        }

        @Test
        @DisplayName("M10c-2: updated samplingRatio is reflected in submitted snapshot")
        void m10c_updatedSamplingRatio_inAppliedSnapshot() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10C_DELTA);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSnapshot().samplingRatio())
                    .as("samplingRatio must reflect the Config Server delta value")
                    .isEqualTo(0.5);
        }

        @Test
        @DisplayName("M10c-3: route map delta (one route removed) is reflected in snapshot")
        void m10c_routeMapDelta_reflected() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10C_DELTA);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            Map<String, Double> routes = client.lastAppliedSnapshot().samplingRouteRatios();
            assertThat(routes)
                    .as("/api/health route must be absent after delta removal")
                    .doesNotContainKey("/api/health");
            assertThat(routes).containsEntry("/api/orders", 0.9);
        }

        @Test
        @DisplayName("M10c-4: source tag on runtime delta delivery is CONFIG_SERVER")
        void m10c_sourceTag_isConfigServer() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10C_DELTA);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSource()).isEqualTo(TracingSettingsDeliverySource.CONFIG_SERVER);
        }

        @Test
        @DisplayName("M10c-5: two sequential refresh events each call applySnapshot once")
        void m10c_twoSequentialRefreshEvents_eachCallApplyOnce() {
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL, M10C_DELTA);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);

            coalescer.onRefreshEvent();
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("each completed refresh cycle must call applySnapshot once (total = 2)")
                    .isEqualTo(2);
        }
    }

    // =========================================================================
    // M10d — STALE conflict: one bounded retry succeeds → APPLIED
    // =========================================================================

    @Nested
    @DisplayName("M10d – STALE conflict: one bounded retry → APPLIED")
    class M10dStaleWithRetry {

        @Test
        @DisplayName("M10d-1: STALE → one retry → APPLIED; total applySnapshot calls = 2")
        void m10d_stale_then_retry_appliesOnce() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL, M10D_RETRY);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("STALE must trigger exactly one retry: total calls = 2")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("M10d-2: final applied snapshot after retry is the rebuilt snapshot")
        void m10d_finalAppliedSnapshot_isRebuiltAfterStale() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL, M10D_RETRY);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSnapshot())
                    .as("final snapshot after retry must be the freshly rebuilt one")
                    .isEqualTo(M10D_RETRY);
        }

        @Test
        @DisplayName("M10d-3: source tag on retry attempt is CONFIG_SERVER")
        void m10d_sourceTag_onRetry_isConfigServer() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL, M10D_RETRY);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.lastAppliedSource())
                    .as("source tag on retry must remain CONFIG_SERVER")
                    .isEqualTo(TracingSettingsDeliverySource.CONFIG_SERVER);
            assertThat(client.allAppliedSources())
                    .as("every apply attempt must be CONFIG_SERVER")
                    .containsOnly(TracingSettingsDeliverySource.CONFIG_SERVER);
        }

        @Test
        @DisplayName("M10d-4: STALE + retry-STALE = no further retry; total calls = 2 (bounded)")
        void m10d_stale_retryAlsoStale_noFurtherRetry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);
            client.enqueueOutcome(TracingSettingsDeliveryResult.STALE);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL, M10D_RETRY);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("bounded: after STALE+STALE coalescer must NOT retry a third time")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("M10d-5: APPLIED on first attempt does NOT trigger any retry")
        void m10d_applied_on_first_attempt_noRetry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.setNextOutcome(TracingSettingsDeliveryResult.APPLIED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("APPLIED on first attempt must produce exactly 1 call, no retry")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("M10d-6: REJECTED does NOT trigger retry (retry is STALE-only)")
        void m10d_rejected_does_not_trigger_retry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.setNextOutcome(TracingSettingsDeliveryResult.REJECTED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("REJECTED must not trigger retry; total calls = 1")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("M10d-7: FAILED does NOT trigger retry (retry is STALE-only)")
        void m10d_failed_does_not_trigger_retry() {
            RecordingSnapshotClient client = new RecordingSnapshotClient();
            client.setNextOutcome(TracingSettingsDeliveryResult.FAILED);

            RecordingSnapshotReader reader = new RecordingSnapshotReader(
                    new AcceptedSettingsWithSequence(BASE_SEQUENCE, RUNTIME_ID, M10_INITIAL));

            ConfigServerSettingsManifestBuilder builder = mock(ConfigServerSettingsManifestBuilder.class);
            when(builder.build()).thenReturn(M10_INITIAL);

            TracingSettingsRefreshCoalescer coalescer =
                    new TracingSettingsRefreshCoalescer(builder, client, reader, Runnable::run);
            coalescer.onRefreshEvent();

            assertThat(client.totalApplyCalls())
                    .as("FAILED must not trigger retry; total calls = 1")
                    .isEqualTo(1);
        }
    }

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    /**
     * Recording stub for {@link TracingSettingsApplier}.
     *
     * <p>Supports a fixed outcome mode and a queue for multi-step STALE→APPLIED scenarios.
     */
    static final class RecordingSnapshotClient implements TracingSettingsApplier {

        private final AtomicInteger totalCalls = new AtomicInteger(0);
        private final AtomicReference<ExpectedSettingsRevision> lastExpectedBase = new AtomicReference<>();
        private final Queue<TracingSettingsDeliverySource> appliedSources = new ConcurrentLinkedQueue<>();
        private volatile CompleteTracingSettings lastSnapshot;
        private volatile TracingSettingsDeliverySource lastSource;

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
        public TracingSettingsDeliveryResult applySnapshot(
                ExpectedSettingsRevision expectedBase,
                CompleteTracingSettings snapshot,
                TracingSettingsDeliverySource source) {
            totalCalls.incrementAndGet();
            lastExpectedBase.set(expectedBase);
            this.lastSnapshot = snapshot;
            this.lastSource = source;
            appliedSources.add(source);
            TracingSettingsDeliveryResult next = outcomeQueue.poll();
            return next != null ? next : defaultOutcome;
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

        TracingSettingsDeliverySource lastAppliedSource() {
            return lastSource;
        }

        List<TracingSettingsDeliverySource> allAppliedSources() {
            return List.copyOf(appliedSources);
        }
    }

    /**
     * Stub for {@link CompleteTracingSettingsReader} with immutable initial state.
     */
    static final class RecordingSnapshotReader implements CompleteTracingSettingsReader {

        private final AtomicReference<AcceptedSettingsWithSequence> state;

        RecordingSnapshotReader(AcceptedSettingsWithSequence initial) {
            this.state = new AtomicReference<>(initial);
        }

        @Override
        public Optional<AcceptedSettingsWithSequence> currentComplete() {
            return Optional.ofNullable(state.get());
        }
    }
}
