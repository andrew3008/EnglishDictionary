package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Design goal for {@code design_class_hierarchy_with_opus}. */
public enum DesignGoal {
    EXTENSIBILITY("extensibility"),
    TESTABILITY("testability"),
    API_COMPATIBILITY("api_compatibility"),
    MIGRATION("migration"),
    CLEAN_ARCHITECTURE("clean_architecture"),
    PERFORMANCE("performance"),
    SECURITY("security"),
    MAINTAINABILITY("maintainability"),
    ALL("all");

    private final String wireValue;

    DesignGoal(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DesignGoal> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
