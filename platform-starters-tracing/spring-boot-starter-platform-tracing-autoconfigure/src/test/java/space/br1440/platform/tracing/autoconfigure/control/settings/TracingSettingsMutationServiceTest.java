package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPrivateControlPlane;

class TracingSettingsMutationServiceTest {

    private static final String RUNTIME_INSTANCE_ID = "instance-1";

    private StubSnapshotReader reader;
    private StubSnapshotClient client;
    private TracingSettingsMutationService adapter;

    @BeforeEach
    void setUp() {
        reader = new StubSnapshotReader();
        client = new StubSnapshotClient();
        adapter = new TracingSettingsMutationService(reader, client);
    }

    @Test
    void setSamplingEnabledChangesExactlyOneLeaf() {
        TracingSettingsDeliveryResult result = adapter.setSamplingEnabled(false);
        assertThat(result).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().samplingEnabled()).isFalse();
        assertThat(client.lastSnapshot().samplingRatio()).isEqualTo(reader.snapshot.samplingRatio());
    }

    @Test
    void setSamplingRatioRejectsOutOfRange() {
        assertThat(adapter.setSamplingRatio(1.1)).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
        assertThat(adapter.setSamplingRatio(-0.1)).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
        assertThat(adapter.setSamplingRatio(Double.NaN)).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
        assertThat(client.applyCallCount())
                .as("invalid candidate must never reach applier")
                .isEqualTo(0);
    }

    @Test
    void setSamplingRatioAcceptsValidRange() {
        assertThat(adapter.setSamplingRatio(0.5)).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().samplingRatio()).isEqualTo(0.5);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsNull() {
        assertThat(adapter.replaceSamplingRouteRatios(null)).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsOversized() {
        // 101 entries: one past the Agent-mirrored MAX_ROUTE_RATIOS bound (100, exact
        // parity with Agent TracingSettings.MAX_ROUTE_ENTRIES).
        Map<String, Double> oversized = new java.util.HashMap<>();
        for (int i = 0; i < 101; i++) oversized.put("/route" + i, 0.5);
        assertThat(adapter.replaceSamplingRouteRatios(oversized)).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosAcceptsExactMaximum() {
        // 100 entries: exactly at the Agent-mirrored bound, must be accepted (not off-by-one).
        Map<String, Double> maximum = new java.util.HashMap<>();
        for (int i = 0; i < 100; i++) maximum.put("/route" + i, 0.5);
        assertThat(adapter.replaceSamplingRouteRatios(maximum)).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsKeyOverMaximumLength() {
        String tooLong = "/" + "a".repeat(255);
        assertThat(adapter.replaceSamplingRouteRatios(Map.of(tooLong, 0.5)))
                .isEqualTo(TracingSettingsDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosAcceptsKeyAtExactMaximumLength() {
        String exact = "a".repeat(255);
        assertThat(adapter.replaceSamplingRouteRatios(Map.of(exact, 0.5)))
                .isEqualTo(TracingSettingsDeliveryResult.APPLIED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsInvalidRatio() {
        assertThat(adapter.replaceSamplingRouteRatios(Map.of("/api", 1.5)))
                .isEqualTo(TracingSettingsDeliveryResult.REJECTED);
    }

    @Test
    void setDiagnosticsLogLevelRejectsUnknownLevel() {
        assertThat(adapter.setDiagnosticsLogLevel("VERBOSE")).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
        assertThat(adapter.setDiagnosticsLogLevel(null)).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
    }

    @Test
    void setDiagnosticsLogLevelAcceptsValidLevels() {
        for (String level : new String[]{"OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"}) {
            assertThat(adapter.setDiagnosticsLogLevel(level)).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        }
    }

    @Test
    void staleIsReturnedWithoutRetry() {
        client.forceOutcome(TracingSettingsDeliveryResult.STALE);
        TracingSettingsDeliveryResult result = adapter.setSamplingEnabled(false);
        assertThat(result).isEqualTo(TracingSettingsDeliveryResult.STALE);
        assertThat(client.applyCallCount()).isEqualTo(1); // no retry
    }

    @Test
    void failedIsReturnedOnClientFailure() {
        client.forceOutcome(TracingSettingsDeliveryResult.FAILED);
        assertThat(adapter.setSamplingEnabled(true)).isEqualTo(TracingSettingsDeliveryResult.FAILED);
    }

    @Test
    void unchangedReturnedWhenAgentReportsUnchanged() {
        client.forceOutcome(TracingSettingsDeliveryResult.UNCHANGED);
        assertThat(adapter.setExporterEnabled(true)).isEqualTo(TracingSettingsDeliveryResult.UNCHANGED);
    }

    @Test
    void failedIsReturnedWhenReaderHasNoSnapshot() {
        reader.empty = true;
        assertThat(adapter.setSamplingEnabled(true)).isEqualTo(TracingSettingsDeliveryResult.FAILED);
        assertThat(client.applyCallCount()).isEqualTo(0);
    }

    @Test
    void exactlyEightOperationsExist() {
        // Verify adapter exposes exactly 8 typed operations via public methods
        long writeMethodCount = java.util.Arrays.stream(TracingSettingsMutationService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getReturnType() == TracingSettingsDeliveryResult.class)
                .count();
        assertThat(writeMethodCount).isEqualTo(8);
    }

    // ── regression: read/write interleaving против реального JMX-клиента и fake Agent ────
    //
    // Выше stub-клиент лишь фиксирует, что MutationService передаёт куда-то ExpectedSettingsRevision,
    // но не может доказать главное свойство P1-фикса: если Agent физически перезапустился МЕЖДУ
    // currentComplete() и applySnapshot() в рамках одного applyChange(), то запись должна быть
    // отвергнута как STALE с кодом RUNTIME_INSTANCE_MISMATCH, ни в каком виде не «дописавшись» в
    // новый инстанс Agent, и без единого повторного вызова applySnapshot внутри самого
    // MutationService (retry — забота коалесера, а не этого сервиса). Для этого read оборачивается
    // декоратором, который вызывает настоящий currentComplete() у реального
    // PrivateJmxTracingSettingsClient, а затем — уже после того, как base был наблюдён и
    // зафиксирован в неизменяемом ExpectedSettingsRevision, — искусственно воспроизводит рестарт
    // Agent-процесса, создавая ровно то окно гонки, которое ExpectedSettingsRevision обязан
    // переживать корректно.
    @Test
    void agentRestartBetweenReadAndApply_rejectsAsStaleWithoutOverwritingNewInstance() throws Exception {
        try (FakeAgentPrivateControlPlane agent =
                     FakeAgentPrivateControlPlane.compliant("instance-before-restart", 5L)) {
            var realClient = agent.client();

            CompleteTracingSettingsReader interleavingReader = () -> {
                Optional<AcceptedSettingsWithSequence> observed = realClient.currentComplete();
                try {
                    agent.restartWithNewInstance("instance-after-restart", 0L);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return observed;
            };

            TracingSettingsMutationService realAdapter =
                    new TracingSettingsMutationService(interleavingReader, realClient);

            TracingSettingsDeliveryResult result = realAdapter.setSamplingEnabled(false);

            assertThat(result)
                    .as("expected base observed against the pre-restart instance must never be "
                            + "accepted by the post-restart instance")
                    .isEqualTo(TracingSettingsDeliveryResult.STALE);
            assertThat(agent.lastResponseMessageCode()).isEqualTo("RUNTIME_INSTANCE_MISMATCH");
            assertThat(agent.applyInvocationCount())
                    .as("MutationService itself must not retry — exactly one applySnapshot call")
                    .isEqualTo(1);
            assertThat(agent.lastRequestRuntimeInstanceId()).isEqualTo("instance-before-restart");
            assertThat(agent.currentAcceptedSequence())
                    .as("the rejected write must not overwrite the new instance's own sequence baseline")
                    .isEqualTo(0L);
        }
    }

    // ---- Stubs ----

    static class StubSnapshotReader implements CompleteTracingSettingsReader {
        private final CompleteTracingSettings snapshot = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        private boolean empty;

        @Override
        public Optional<AcceptedSettingsWithSequence> currentComplete() {
            if (empty) return Optional.empty();
            return Optional.of(new AcceptedSettingsWithSequence(42L, RUNTIME_INSTANCE_ID, snapshot));
        }
    }

    static class StubSnapshotClient implements TracingSettingsApplier {
        private TracingSettingsDeliveryResult forcedOutcome = TracingSettingsDeliveryResult.APPLIED;
        private CompleteTracingSettings lastSnapshot;
        private int applyCount;

        public void forceOutcome(TracingSettingsDeliveryResult outcome) { this.forcedOutcome = outcome; }
        public CompleteTracingSettings lastSnapshot() { return lastSnapshot; }
        public int applyCallCount() { return applyCount; }

        @Override
        public TracingSettingsDeliveryResult applySnapshot(ExpectedSettingsRevision expectedBase,
                CompleteTracingSettings snapshot, TracingSettingsDeliverySource source) {
            this.lastSnapshot = snapshot;
            this.applyCount++;
            return forcedOutcome;
        }
    }
}
