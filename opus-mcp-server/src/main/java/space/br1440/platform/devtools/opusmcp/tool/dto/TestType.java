package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Test type for {@code review_tests_with_opus}. */
public enum TestType {
    UNIT("unit"),
    INTEGRATION("integration"),
    CONTRACT("contract"),
    COMPONENT("component"),
    SLICE("slice"),
    E2E("e2e"),
    PROPERTY("property"),
    PERFORMANCE("performance"),
    UNKNOWN("unknown");

    private final String wireValue;

    TestType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<TestType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
