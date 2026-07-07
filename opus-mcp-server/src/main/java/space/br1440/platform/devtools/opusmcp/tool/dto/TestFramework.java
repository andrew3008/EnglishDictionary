package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Test framework for {@code review_tests_with_opus}. */
public enum TestFramework {
    JUNIT5("junit5"),
    TESTNG("testng"),
    SPOCK("spock"),
    KOTEST("kotest"),
    GO_TESTING("go_testing"),
    PYTEST("pytest"),
    UNKNOWN("unknown");

    private final String wireValue;

    TestFramework(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<TestFramework> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
