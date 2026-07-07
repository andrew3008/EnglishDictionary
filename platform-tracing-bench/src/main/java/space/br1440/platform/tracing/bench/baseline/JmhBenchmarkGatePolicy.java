package space.br1440.platform.tracing.bench.baseline;

import java.util.Set;

/**
 * Hard-gate vs diagnostic-only JMH benchmark classification (PR-9H-B).
 * <p>
 * SampleTime ({@code mode=sample}) remains diagnostic via {@link JmhBaselineComparator}.
 * Validation strict exception-path benchmark is diagnostic-only by name convention.
 */
public final class JmhBenchmarkGatePolicy {

    public static final String VALIDATION_BENCHMARK_CLASS =
            "space.br1440.platform.tracing.bench.ValidatingSpanProcessorBenchmark";

    private static final Set<String> VALIDATION_HARD_GATE_SUFFIXES = Set.of(
            ".validationDisabled",
            ".validationLenientValidSpan",
            ".validationLenientMissingRequiredAttr",
            ".validationStrictAllowedValidSpan",
            ".holderCurrentRead",
            ".policySnapshotRead");

    private static final String DIAGNOSTIC_VALIDATION_SUFFIX = ".validationStrictAllowedMissingAttrDiagnostic";

    private JmhBenchmarkGatePolicy() {
    }

    public static boolean isValidationHardGateBenchmark(String fullBenchmarkName) {
        if (fullBenchmarkName == null || !fullBenchmarkName.startsWith(VALIDATION_BENCHMARK_CLASS + ".")) {
            return false;
        }
        if (isDiagnosticBenchmark(fullBenchmarkName)) {
            return false;
        }
        for (String suffix : VALIDATION_HARD_GATE_SUFFIXES) {
            if (fullBenchmarkName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDiagnosticBenchmark(String fullBenchmarkName) {
        if (fullBenchmarkName == null) {
            return false;
        }
        return fullBenchmarkName.endsWith(DIAGNOSTIC_VALIDATION_SUFFIX);
    }

    public static GateClass gateClassFor(String mode, String fullBenchmarkName) {
        if ("sample".equals(mode) || isDiagnosticBenchmark(fullBenchmarkName)) {
            return GateClass.DIAGNOSTIC;
        }
        return GateClass.HARD;
    }

    enum GateClass {
        HARD,
        DIAGNOSTIC
    }
}
