package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Review focus for {@code review_tests_with_opus}. */
public enum TestReviewFocus {
    CORRECTNESS("correctness"),
    COVERAGE("coverage"),
    FLAKINESS("flakiness"),
    MAINTAINABILITY("maintainability"),
    ASSERTIONS("assertions"),
    MOCKS("mocks"),
    INTEGRATION_BOUNDARIES("integration_boundaries"),
    SECURITY("security"),
    PERFORMANCE("performance"),
    ALL("all");

    private final String wireValue;

    TestReviewFocus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<TestReviewFocus> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
