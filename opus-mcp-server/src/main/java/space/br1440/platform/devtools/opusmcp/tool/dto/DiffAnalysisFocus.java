package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum DiffAnalysisFocus {
    CORRECTNESS("correctness"),
    SECURITY("security"),
    PERFORMANCE("performance"),
    TESTS("tests"),
    MAINTAINABILITY("maintainability"),
    ARCHITECTURE("architecture"),
    MIGRATION("migration"),
    ALL("all");

    private final String wireValue;

    DiffAnalysisFocus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DiffAnalysisFocus> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
