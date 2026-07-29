package space.br1440.platform.tracing.autoconfigure.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.configuration.actuator.SpringActuatorTracingPolicyAdapter;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpringActuatorTracingPolicyAdapterTest {

    private static final String RUNTIME_INSTANCE_ID = "instance-1";

    private StubSnapshotReader reader;
    private StubSnapshotClient client;
    private SpringActuatorTracingPolicyAdapter adapter;

    @BeforeEach
    void setUp() {
        reader = new StubSnapshotReader();
        client = new StubSnapshotClient();
        adapter = new SpringActuatorTracingPolicyAdapter(reader, client);
    }

    @Test
    void setSamplingEnabledChangesExactlyOneLeaf() {
        TracingPolicyDeliveryResult result = adapter.setSamplingEnabled(false);
        assertThat(result).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().samplingEnabled()).isFalse();
        assertThat(client.lastSnapshot().samplingRatio()).isEqualTo(reader.snapshot.samplingRatio());
    }

    @Test
    void setSamplingRatioRejectsOutOfRange() {
        assertThat(adapter.setSamplingRatio(1.1)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
        assertThat(adapter.setSamplingRatio(-0.1)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void setSamplingRatioAcceptsValidRange() {
        assertThat(adapter.setSamplingRatio(0.5)).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
        assertThat(client.lastSnapshot().samplingRatio()).isEqualTo(0.5);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsNull() {
        assertThat(adapter.replaceSamplingRouteRatios(null)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsOversized() {
        // 101 entries: one past the Agent-mirrored MAX_ROUTE_RATIOS bound (100, exact
        // parity with Agent EffectiveTracingPolicyValidator.MAX_ROUTE_ENTRIES).
        Map<String, Double> oversized = new java.util.HashMap<>();
        for (int i = 0; i < 101; i++) oversized.put("/route" + i, 0.5);
        assertThat(adapter.replaceSamplingRouteRatios(oversized)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosAcceptsExactMaximum() {
        // 100 entries: exactly at the Agent-mirrored bound, must be accepted (not off-by-one).
        Map<String, Double> maximum = new java.util.HashMap<>();
        for (int i = 0; i < 100; i++) maximum.put("/route" + i, 0.5);
        assertThat(adapter.replaceSamplingRouteRatios(maximum)).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsKeyOverMaximumLength() {
        String tooLong = "/" + "a".repeat(255);
        assertThat(adapter.replaceSamplingRouteRatios(Map.of(tooLong, 0.5)))
                .isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosAcceptsKeyAtExactMaximumLength() {
        String exact = "a".repeat(255);
        assertThat(adapter.replaceSamplingRouteRatios(Map.of(exact, 0.5)))
                .isEqualTo(TracingPolicyDeliveryResult.APPLIED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsInvalidRatio() {
        assertThat(adapter.replaceSamplingRouteRatios(Map.of("/api", 1.5)))
                .isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void setDiagnosticsLogLevelRejectsUnknownLevel() {
        assertThat(adapter.setDiagnosticsLogLevel("VERBOSE")).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
        assertThat(adapter.setDiagnosticsLogLevel(null)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void setDiagnosticsLogLevelAcceptsValidLevels() {
        for (String level : new String[]{"OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"}) {
            assertThat(adapter.setDiagnosticsLogLevel(level)).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
        }
    }

    @Test
    void staleIsReturnedWithoutRetry() {
        client.forceOutcome(TracingPolicyDeliveryResult.STALE);
        TracingPolicyDeliveryResult result = adapter.setSamplingEnabled(false);
        assertThat(result).isEqualTo(TracingPolicyDeliveryResult.STALE);
        assertThat(client.applyCallCount()).isEqualTo(1); // no retry
    }

    @Test
    void failedIsReturnedOnClientFailure() {
        client.forceOutcome(TracingPolicyDeliveryResult.FAILED);
        assertThat(adapter.setSamplingEnabled(true)).isEqualTo(TracingPolicyDeliveryResult.FAILED);
    }

    @Test
    void unchangedReturnedWhenAgentReportsUnchanged() {
        client.forceOutcome(TracingPolicyDeliveryResult.UNCHANGED);
        assertThat(adapter.setExporterEnabled(true)).isEqualTo(TracingPolicyDeliveryResult.UNCHANGED);
    }

    @Test
    void failedIsReturnedWhenReaderHasNoSnapshot() {
        reader.empty = true;
        assertThat(adapter.setSamplingEnabled(true)).isEqualTo(TracingPolicyDeliveryResult.FAILED);
        assertThat(client.applyCallCount()).isEqualTo(0);
    }

    @Test
    void exactlyEightOperationsExist() {
        // Verify adapter exposes exactly 8 typed operations via public methods
        long writeMethodCount = java.util.Arrays.stream(SpringActuatorTracingPolicyAdapter.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getReturnType() == TracingPolicyDeliveryResult.class)
                .count();
        assertThat(writeMethodCount).isEqualTo(8);
    }

    // ---- Stubs ----

    static class StubSnapshotReader implements AcceptedTracingPolicySnapshotReader {
        private final CompleteTracingPolicySnapshot snapshot = CompleteTracingPolicySnapshot.builder()
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
        public Optional<AcceptedSnapshotWithSequence> currentComplete() {
            if (empty) return Optional.empty();
            return Optional.of(new AcceptedSnapshotWithSequence(42L, RUNTIME_INSTANCE_ID, snapshot));
        }
    }

    static class StubSnapshotClient implements TracingPolicySnapshotClient {
        private TracingPolicyDeliveryResult forcedOutcome = TracingPolicyDeliveryResult.APPLIED;
        private CompleteTracingPolicySnapshot lastSnapshot;
        private int applyCount;

        public void forceOutcome(TracingPolicyDeliveryResult outcome) { this.forcedOutcome = outcome; }
        public CompleteTracingPolicySnapshot lastSnapshot() { return lastSnapshot; }
        public int applyCallCount() { return applyCount; }

        @Override
        public TracingPolicyDeliveryResult applySnapshot(long seq, CompleteTracingPolicySnapshot snapshot,
                TracingPolicySource source) {
            this.lastSnapshot = snapshot;
            this.applyCount++;
            return forcedOutcome;
        }
    }
}
