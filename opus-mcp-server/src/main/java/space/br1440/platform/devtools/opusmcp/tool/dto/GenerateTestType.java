package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/**
 * Test type for {@code generate_tests_with_opus}.
 *
 * <p>The wire values MUST stay in lock-step with the {@code testType} enum advertised by
 * {@link space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool#INPUT_SCHEMA_JSON}. This is a
 * dedicated enum (not the {@link TestType} used by {@code review_tests_with_opus}) precisely so the
 * generate-tests schema and its argument binding cannot drift apart. In particular the generate-tests
 * surface accepts {@code regression} and {@code all}, which the review-tests enum does not.
 */
public enum GenerateTestType {
    UNIT("unit"),
    INTEGRATION("integration"),
    CONTRACT("contract"),
    SLICE("slice"),
    PROPERTY("property"),
    REGRESSION("regression"),
    ALL("all");

    private final String wireValue;

    GenerateTestType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<GenerateTestType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
