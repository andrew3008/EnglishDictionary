package space.br1440.platform.tracing.bench.baseline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JmhBenchmarkGatePolicyTest {

    private static final String CLASS = JmhBenchmarkGatePolicy.VALIDATION_BENCHMARK_CLASS;

    @Test
    void validationHardGateMethods_recognized() {
        assertThat(JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(CLASS + ".validationDisabled")).isTrue();
        assertThat(JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(CLASS + ".validationLenientValidSpan")).isTrue();
        assertThat(JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(CLASS + ".validationLenientMissingRequiredAttr")).isTrue();
        assertThat(JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(CLASS + ".validationStrictAllowedValidSpan")).isTrue();
        assertThat(JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(CLASS + ".holderCurrentRead")).isTrue();
        assertThat(JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(CLASS + ".policySnapshotRead")).isTrue();
    }

    @Test
    void validationDiagnosticMethod_excludedFromHardGate() {
        String diagnostic = CLASS + ".validationStrictAllowedMissingAttrDiagnostic";
        assertThat(JmhBenchmarkGatePolicy.isDiagnosticBenchmark(diagnostic)).isTrue();
        assertThat(JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(diagnostic)).isFalse();
        assertThat(JmhBenchmarkGatePolicy.gateClassFor("avgt", diagnostic))
                .isEqualTo(JmhBenchmarkGatePolicy.GateClass.DIAGNOSTIC);
    }

    @Test
    void validationHardGateMethods_useHardGateForAvgt() {
        assertThat(JmhBenchmarkGatePolicy.gateClassFor(
                "avgt", CLASS + ".validationLenientValidSpan"))
                .isEqualTo(JmhBenchmarkGatePolicy.GateClass.HARD);
    }
}
