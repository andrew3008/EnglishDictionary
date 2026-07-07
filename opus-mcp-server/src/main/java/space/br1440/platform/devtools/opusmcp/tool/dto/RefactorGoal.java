package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum RefactorGoal {
    READABILITY("readability"),
    MAINTAINABILITY("maintainability"),
    PERFORMANCE("performance"),
    SECURITY("security"),
    TESTABILITY("testability"),
    ARCHITECTURE("architecture"),
    MIGRATION("migration"),
    API_COMPATIBILITY("api_compatibility"),
    ALL("all");

    private final String wireValue;

    RefactorGoal(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<RefactorGoal> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
