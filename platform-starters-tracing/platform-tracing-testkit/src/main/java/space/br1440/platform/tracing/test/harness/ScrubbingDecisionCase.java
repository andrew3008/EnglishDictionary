package space.br1440.platform.tracing.test.harness;

import java.util.List;

/**
 * Строка матрицы characterization-тестов scrubbing.
 */
public record ScrubbingDecisionCase(
        String caseId,
        String inputKey,
        String inputValue,
        List<String> ruleNames,
        String hmacKey,
        String expectedStringValue,
        boolean valuePreservedExactly,
        String expectedFailureMode) {

    public ScrubbingDecisionCase {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId required");
        }
        ruleNames = ruleNames == null ? List.of() : List.copyOf(ruleNames);
    }
}
