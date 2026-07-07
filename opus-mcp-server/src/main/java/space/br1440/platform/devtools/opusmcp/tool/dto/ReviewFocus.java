package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum ReviewFocus {
    CORRECTNESS("correctness"),
    SECURITY("security"),
    PERFORMANCE("performance"),
    MAINTAINABILITY("maintainability"),
    TESTS("tests"),
    ARCHITECTURE("architecture"),
    ALL("all");

    private final String wireValue;

    ReviewFocus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ReviewFocus> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
