package space.br1440.platform.tracing.test.harness;

/**
 * Строка матрицы characterization-тестов validation policy.
 */
public record ValidationDecisionCase(
        String caseId,
        boolean enabled,
        boolean strict,
        boolean hasPlatformType,
        boolean hasPlatformResult,
        boolean expectExport,
        boolean expectMissingAttribute,
        Class<? extends Throwable> expectedThrowable) {

    public ValidationDecisionCase {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId required");
        }
    }
}
