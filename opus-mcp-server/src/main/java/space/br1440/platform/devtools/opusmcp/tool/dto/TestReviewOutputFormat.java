package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code review_tests_with_opus}. */
public enum TestReviewOutputFormat {
    STRUCTURED_REVIEW("structured_review"),
    CHECKLIST("checklist"),
    RISK_REVIEW("risk_review"),
    COVERAGE_REVIEW("coverage_review"),
    CI_READINESS("ci_readiness");

    private final String wireValue;

    TestReviewOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<TestReviewOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
