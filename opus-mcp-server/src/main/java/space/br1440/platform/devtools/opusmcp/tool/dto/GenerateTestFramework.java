package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/**
 * Test framework for {@code generate_tests_with_opus}.
 *
 * <p>The wire values MUST stay in lock-step with the {@code testFramework} enum advertised by
 * {@link space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool#INPUT_SCHEMA_JSON}. This is a
 * dedicated enum (not the {@link TestFramework} used by {@code review_tests_with_opus}) precisely so
 * the generate-tests schema and its argument binding cannot drift apart.
 */
public enum GenerateTestFramework {
    JUNIT5("junit5"),
    TESTNG("testng"),
    MOCKITO("mockito"),
    ASSERTJ("assertj"),
    SPRING_BOOT_TEST("spring_boot_test"),
    KOTEST("kotest"),
    GO_TEST("go_test"),
    OTHER("other");

    private final String wireValue;

    GenerateTestFramework(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<GenerateTestFramework> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
