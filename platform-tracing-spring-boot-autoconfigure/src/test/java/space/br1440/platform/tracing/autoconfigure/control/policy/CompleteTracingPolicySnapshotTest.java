package space.br1440.platform.tracing.autoconfigure.control.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompleteTracingPolicySnapshotTest {

    // The builder deliberately rejects a partial (fewer than eight leaves) snapshot — see
    // P4ArchitectureTest.snapshotBuilderRejectsPartialSnapshot() — so every test here supplies
    // all eight leaves, mirroring the only shape the real production adapters ever build.
    private static CompleteTracingPolicySnapshot.Builder fullBuilder() {
        return CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO");
    }

    @Test
    void builderProducesAllEightLeaves() {
        CompleteTracingPolicySnapshot s = CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5)
                .samplingRouteRatios(Map.of("/api", 0.1))
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("DEBUG")
                .build();

        assertThat(s.samplingEnabled()).isTrue();
        assertThat(s.samplingRatio()).isEqualTo(0.5);
        assertThat(s.samplingRouteRatios()).containsEntry("/api", 0.1);
        assertThat(s.validationEnabled()).isTrue();
        assertThat(s.validationStrict()).isFalse();
        assertThat(s.exporterEnabled()).isTrue();
        assertThat(s.traceControlHeadersEnabled()).isTrue();
        assertThat(s.diagnosticsLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    void withMethodsProduceCopyWithOneDiff() {
        CompleteTracingPolicySnapshot original = fullBuilder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .exporterEnabled(true)
                .build();

        CompleteTracingPolicySnapshot modified = original.withSamplingEnabled(false);
        assertThat(modified.samplingEnabled()).isFalse();
        assertThat(modified.samplingRatio()).isEqualTo(original.samplingRatio());
        assertThat(modified.exporterEnabled()).isEqualTo(original.exporterEnabled());
    }

    @Test
    void snapshotIsImmutable() {
        Map<String, Double> mutable = new java.util.HashMap<>();
        mutable.put("/api", 0.5);
        CompleteTracingPolicySnapshot s = fullBuilder()
                .samplingRouteRatios(mutable)
                .build();
        mutable.put("/other", 0.9);
        assertThat(s.samplingRouteRatios()).doesNotContainKey("/other");
    }

    @Test
    void equalSnapshotsAreEqual() {
        CompleteTracingPolicySnapshot a = fullBuilder().build();
        CompleteTracingPolicySnapshot b = fullBuilder().build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
