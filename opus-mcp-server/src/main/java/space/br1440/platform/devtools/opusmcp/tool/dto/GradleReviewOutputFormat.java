package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code review_gradle_build_with_opus}. */
public enum GradleReviewOutputFormat {
    STRUCTURED_REVIEW("structured_review"),
    CHECKLIST("checklist"),
    RISK_REVIEW("risk_review"),
    BUILD_HEALTH("build_health"),
    MIGRATION_REVIEW("migration_review");

    private final String wireValue;

    GradleReviewOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<GradleReviewOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
