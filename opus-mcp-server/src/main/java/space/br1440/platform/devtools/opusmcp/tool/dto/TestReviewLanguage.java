package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Language for {@code review_tests_with_opus} (reduced set: no mdx/gradle). */
public enum TestReviewLanguage {
    JAVA("java"),
    GO("go"),
    KOTLIN("kotlin"),
    SQL("sql"),
    OTHER("other");

    private final String wireValue;

    TestReviewLanguage(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<TestReviewLanguage> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
