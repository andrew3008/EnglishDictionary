package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;

class TracingSettingsMutationServiceTest {

    private static final String AGENT_INSTANCE_ID = "6f9f953d-f06f-48c5-b68b-f89ad09daee5";
    private static final String BEFORE_RESTART_AGENT_INSTANCE_ID = "26b5d135-bf42-47e8-8821-bf16527b593c";
    private static final String AFTER_RESTART_AGENT_INSTANCE_ID = "b59c3cc9-588e-4f07-ac77-653a6aaf4be7";

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
        Map<String, Double> oversized = new java.util.HashMap<>();
        for (int i = 0; i < 101; i++) {
            oversized.put("/route" + i, 0.5);
        }

        assertThat(adapter.replaceSamplingRouteRatios(oversized)).isEqualTo(TracingSettingsDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosAcceptsExactMaximum() {
        Map<String, Double> maximum = new java.util.HashMap<>();
        for (int i = 0; i < 100; i++) {
            maximum.put("/route" + i, 0.5);
        }

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
    void setValidationLevelChangesExactlyThatLeaf() {
        TracingSettingsDeliveryResult result = adapter.setValidationLevel("ERROR");

        assertThat(result).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().validationLevel()).isEqualTo("ERROR");
        assertThat(client.lastSnapshot().exporterEnabled()).isTrue();
    }

    @Test
    void setExporterEnabledDoesNotChangeValidationLevel() {
        TracingSettingsDeliveryResult result = adapter.setExporterEnabled(false);

        assertThat(result).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().exporterEnabled()).isFalse();
        assertThat(client.lastSnapshot().validationLevel()).isEqualTo("WARNING");
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
    void exactlySevenOperationsExist() {
        long writeMethodCount = java.util.Arrays.stream(TracingSettingsMutationService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getReturnType() == TracingSettingsDeliveryResult.class)
                .count();
        assertThat(writeMethodCount).isEqualTo(7);
    }

    @Test
    void agentRestartBetweenReadAndApplyFailsClosedBeforeMutation() throws Exception {
        // given
        try (FakeAgentPlatformJmxPlane agent = FakeAgentPlatformJmxPlane.compliant(
                BEFORE_RESTART_AGENT_INSTANCE_ID,
                5L
        )) {
            var realClient = agent.client();

            CompleteTracingSettingsReader interleavingReader = () -> {
                Optional<AcceptedSettingsWithSequence> observed = realClient.currentComplete();
                try {
                    agent.restartWithNewInstance(AFTER_RESTART_AGENT_INSTANCE_ID, 0L);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return observed;
            };

            TracingSettingsMutationService realAdapter =
                    new TracingSettingsMutationService(interleavingReader, realClient);

            // when
            // then
            assertThatThrownBy(() -> realAdapter.setSamplingEnabled(false))
                    .isInstanceOf(AgentReadinessCoherenceException.class)
                    .satisfies(failure -> assertThat(
                            ((AgentReadinessCoherenceException) failure).failureCode())
                            .isEqualTo(AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED));
            assertThat(agent.applyInvocationCount()).isZero();
            assertThat(agent.lastRequestAgentInstanceId()).isNull();
            assertThat(agent.currentAcceptedSequence()).isZero();
        }
    }

    static class StubSnapshotReader implements CompleteTracingSettingsReader {
        private final CompleteTracingSettings snapshot = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
        private boolean empty;

        @Override
        public Optional<AcceptedSettingsWithSequence> currentComplete() {
            if (empty) return Optional.empty();
            return Optional.of(new AcceptedSettingsWithSequence(42L, AGENT_INSTANCE_ID, snapshot));
        }
    }

    static class StubSnapshotClient implements TracingSettingsApplier {
        private TracingSettingsDeliveryResult forcedOutcome = TracingSettingsDeliveryResult.APPLIED;
        private CompleteTracingSettings lastSnapshot;
        private int applyCount;

        public void forceOutcome(TracingSettingsDeliveryResult outcome) {
            this.forcedOutcome = outcome;
        }

        public CompleteTracingSettings lastSnapshot() {
            return lastSnapshot;
        }

        public int applyCallCount() {
            return applyCount;
        }

        @Override
        public TracingSettingsDeliveryResult applySnapshot(ExpectedSettingsRevision expectedBase,
                                                           CompleteTracingSettings snapshot,
                                                           TracingSettingsDeliverySource source) {
            this.lastSnapshot = snapshot;
            this.applyCount++;
            return forcedOutcome;
        }
    }
}
