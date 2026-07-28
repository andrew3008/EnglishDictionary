package space.br1440.platform.tracing.autoconfigure.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.configuration.actuator.SpringActuatorTracingPolicyAdapter;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpringActuatorTracingPolicyAdapterTest {

    private StubSnapshotClient stub;
    private SpringActuatorTracingPolicyAdapter adapter;

    @BeforeEach
    void setUp() {
        stub = new StubSnapshotClient();
        adapter = new SpringActuatorTracingPolicyAdapter(stub);
    }

    @Test
    void setSamplingEnabledChangesExactlyOneLeaf() {
        TracingPolicyDeliveryResult result = adapter.setSamplingEnabled(false);
        assertThat(result).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
        assertThat(stub.lastSnapshot().samplingEnabled()).isFalse();
        assertThat(stub.lastSnapshot().samplingRatio()).isEqualTo(stub.currentView().samplingRatio());
    }

    @Test
    void setSamplingRatioRejectsOutOfRange() {
        assertThat(adapter.setSamplingRatio(1.1)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
        assertThat(adapter.setSamplingRatio(-0.1)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void setSamplingRatioAcceptsValidRange() {
        assertThat(adapter.setSamplingRatio(0.5)).isEqualTo(TracingPolicyDeliveryResult.APPLIED);
        assertThat(stub.lastSnapshot().samplingRatio()).isEqualTo(0.5);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsNull() {
        assertThat(adapter.replaceSamplingRouteRatios(null)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
    }

    @Test
    void replaceSamplingRouteRatiosRejectsOversized() {
        Map<String, Double> oversized = new java.util.HashMap<>();
        for (int i = 0; i < 65; i++) oversized.put("/route" + i, 0.5);
        assertThat(adapter.replaceSamplingRouteRatios(oversized)).isEqualTo(TracingPolicyDeliveryResult.REJECTED);
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
        stub.forceOutcome(TracingPolicyDeliveryResult.STALE);
        TracingPolicyDeliveryResult result = adapter.setSamplingEnabled(false);
        assertThat(result).isEqualTo(TracingPolicyDeliveryResult.STALE);
        assertThat(stub.applyCallCount()).isEqualTo(1); // no retry
    }

    @Test
    void failedIsReturnedOnClientFailure() {
        stub.forceOutcome(TracingPolicyDeliveryResult.FAILED);
        assertThat(adapter.setSamplingEnabled(true)).isEqualTo(TracingPolicyDeliveryResult.FAILED);
    }

    @Test
    void unchangedReturnedWhenAgentReportsUnchanged() {
        stub.forceOutcome(TracingPolicyDeliveryResult.UNCHANGED);
        assertThat(adapter.setExporterEnabled(true)).isEqualTo(TracingPolicyDeliveryResult.UNCHANGED);
    }

    @Test
    void exactlyEightOperationsExist() throws Exception {
        // Verify adapter exposes exactly 8 typed operations via public methods
        long writeMethodCount = java.util.Arrays.stream(SpringActuatorTracingPolicyAdapter.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getReturnType() == TracingPolicyDeliveryResult.class)
                .count();
        assertThat(writeMethodCount).isEqualTo(8);
    }

    // ---- Stub ----

    static class StubSnapshotClient implements TracingPolicySnapshotClient {
        private TracingPolicyDeliveryResult forcedOutcome = TracingPolicyDeliveryResult.APPLIED;
        private CompleteTracingPolicySnapshot lastSnapshot;
        private int applyCount;

        private final AcceptedTracingPolicyView view = new AcceptedTracingPolicyView(
                "instance-1", 42L, "digest",
                true, 1.0, 0,
                true, false, true, true, "INFO",
                true, "APPLIED"
        );

        public void forceOutcome(TracingPolicyDeliveryResult outcome) { this.forcedOutcome = outcome; }
        public CompleteTracingPolicySnapshot lastSnapshot() { return lastSnapshot; }
        public AcceptedTracingPolicyView currentView() { return view; }
        public int applyCallCount() { return applyCount; }

        @Override
        public TracingPolicyDeliveryResult applySnapshot(long seq, CompleteTracingPolicySnapshot snapshot,
                TracingPolicySource source) {
            this.lastSnapshot = snapshot;
            this.applyCount++;
            return forcedOutcome;
        }

        @Override
        public Optional<AcceptedTracingPolicyView> current() {
            return Optional.of(view);
        }
    }
}
