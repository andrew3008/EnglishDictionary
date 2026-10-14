package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;

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
        adapter = new TracingSettingsMutationService(recordingSettingsWriter(client, reader));
    }

    @Test
    void setSamplingEnabledChangesExactlyOneLeaf() {
        SettingsDelivery delivery = adapter.setSamplingEnabled(false);
        assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().samplingEnabled()).isFalse();
        assertThat(client.lastSnapshot().samplingRatio()).isEqualTo(reader.snapshot.samplingRatio());
        assertThat(client.lastWriter()).isEqualTo(SettingsWriter.ACTUATOR);
    }

    @Test
    void setSamplingRatioRejectsOutOfRange() {
        assertThatThrownBy(() -> adapter.setSamplingRatio(1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.setSamplingRatio(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.setSamplingRatio(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.applyCallCount())
                .as("invalid candidate must never reach applier")
                .isEqualTo(0);
    }

    @Test
    void setSamplingRatioAcceptsValidRange() {
        assertThat(adapter.setSamplingRatio(0.5).outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().samplingRatio()).isEqualTo(0.5);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsNull() {
        assertThatThrownBy(() -> adapter.replaceSamplingRouteRatios(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.applyCallCount()).isZero();
    }

    @Test
    void replaceSamplingRouteRatiosRejectsOversized() {
        Map<String, Double> oversized = new java.util.HashMap<>();
        for (int i = 0; i < 101; i++) {
            oversized.put("/route" + i, 0.5);
        }

        assertThatThrownBy(() -> adapter.replaceSamplingRouteRatios(oversized))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.applyCallCount()).isZero();
    }

    @Test
    void replaceSamplingRouteRatiosAcceptsExactMaximum() {
        Map<String, Double> maximum = new java.util.HashMap<>();
        for (int i = 0; i < 100; i++) {
            maximum.put("/route" + i, 0.5);
        }

        assertThat(adapter.replaceSamplingRouteRatios(maximum).outcome())
                .isEqualTo(TracingSettingsDeliveryResult.APPLIED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsKeyOverMaximumLength() {
        String tooLong = "/" + "a".repeat(255);
        assertThatThrownBy(() -> adapter.replaceSamplingRouteRatios(Map.of(tooLong, 0.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.applyCallCount()).isZero();
    }

    @Test
    void replaceSamplingRouteRatiosAcceptsKeyAtExactMaximumLength() {
        String exact = "a".repeat(255);
        assertThat(adapter.replaceSamplingRouteRatios(Map.of(exact, 0.5)).outcome())
                .isEqualTo(TracingSettingsDeliveryResult.APPLIED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsInvalidRatio() {
        assertThatThrownBy(() -> adapter.replaceSamplingRouteRatios(Map.of("/api", 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.applyCallCount()).isZero();
    }

    @Test
    void setValidationLevelChangesExactlyThatLeaf() {
        SettingsDelivery delivery = adapter.setValidationLevel("ERROR");

        assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().validationLevel()).isEqualTo("ERROR");
        assertThat(client.lastSnapshot().exporterEnabled()).isTrue();
    }

    @Test
    void setExporterEnabledDoesNotChangeValidationLevel() {
        SettingsDelivery delivery = adapter.setExporterEnabled(false);

        assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().exporterEnabled()).isFalse();
        assertThat(client.lastSnapshot().validationLevel()).isEqualTo("WARNING");
    }

    @Test
    void setDiagnosticsLogLevelRejectsUnknownLevel() {
        assertThatThrownBy(() -> adapter.setDiagnosticsLogLevel("VERBOSE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.setDiagnosticsLogLevel(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.applyCallCount()).isZero();
    }

    @Test
    void setDiagnosticsLogLevelAcceptsValidLevels() {
        for (String level : new String[]{"OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"}) {
            assertThat(adapter.setDiagnosticsLogLevel(level).outcome())
                    .isEqualTo(TracingSettingsDeliveryResult.APPLIED);
        }
    }

    @Test
    void staleIsReturnedWithoutRetry() {
        client.forceOutcome(TracingSettingsDeliveryResult.STALE);
        SettingsDelivery delivery = adapter.setSamplingEnabled(false);
        assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.STALE);
        assertThat(client.applyCallCount()).isEqualTo(1);
    }

    @Test
    void failedIsReturnedOnClientFailure() {
        client.forceOutcome(TracingSettingsDeliveryResult.FAILED);
        assertThat(adapter.setSamplingEnabled(true).outcome()).isEqualTo(TracingSettingsDeliveryResult.FAILED);
    }

    @Test
    void unchangedReturnedWhenAgentReportsUnchanged() {
        client.forceOutcome(TracingSettingsDeliveryResult.UNCHANGED);
        assertThat(adapter.setExporterEnabled(true).outcome()).isEqualTo(TracingSettingsDeliveryResult.UNCHANGED);
    }

    @Test
    void failedIsReturnedWhenReaderHasNoSnapshot() {
        reader.empty = true;
        assertThatThrownBy(() -> adapter.setSamplingEnabled(true))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage("Accepted tracing settings are unavailable");
        assertThat(client.applyCallCount()).isEqualTo(0);
    }

    @Test
    void exactlySevenOperationsExist() {
        long writeMethodCount = java.util.Arrays.stream(TracingSettingsMutationService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getReturnType() == SettingsDelivery.class)
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

            Supplier<Optional<AcceptedSettings>> interleavingReader = () -> {
                Optional<AcceptedSettings> observed = realClient.current();
                try {
                    agent.restartWithNewInstance(AFTER_RESTART_AGENT_INSTANCE_ID, 0L);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return observed;
            };

            TracingSettingsMutationService realAdapter =
                    new TracingSettingsMutationService(recordingSettingsWriter(
                            realClient::applySnapshot,
                            interleavingReader
                    ));

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

    static class StubSnapshotReader implements Supplier<Optional<AcceptedSettings>> {
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
        public Optional<AcceptedSettings> get() {
            if (empty) return Optional.empty();
            return Optional.of(AcceptedSettingsTestFixtures.bootstrapAgentDefaults(42L, AGENT_INSTANCE_ID, snapshot));
        }
    }

    static class StubSnapshotClient implements RecordingSettingsWriter.ApplyBehavior {
        private TracingSettingsDeliveryResult forcedOutcome = TracingSettingsDeliveryResult.APPLIED;
        private CompleteTracingSettings lastSnapshot;
        private SettingsWriter lastWriter;
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

        public SettingsWriter lastWriter() {
            return lastWriter;
        }

        @Override
        public SettingsDelivery applySnapshot(SettingsRevision expectedBase,
                                              CompleteTracingSettings snapshot,
                                              SettingsWriter writer) {
            this.lastSnapshot = snapshot;
            this.lastWriter = writer;
            this.applyCount++;
            return new SettingsDelivery(
                    "00000000-0000-0000-0000-000000000001",
                    forcedOutcome,
                    messageCode(forcedOutcome)
            );
        }

        private static String messageCode(TracingSettingsDeliveryResult outcome) {
            return switch (outcome) {
                case APPLIED, UNCHANGED -> "NONE";
                case REJECTED -> "INVALID_VALUE";
                case STALE -> "ACCEPTED_SEQUENCE_MISMATCH";
                case FAILED -> "INTERNAL_FAILURE";
            };
        }
    }
}
