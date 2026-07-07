package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Review focus for {@code review_architecture_with_opus}. */
public enum ArchitectureReviewFocus {
    API_COMPATIBILITY("api_compatibility"),
    OBSERVABILITY("observability"),
    SECURITY("security"),
    MIGRATION("migration"),
    TESTING("testing"),
    PERFORMANCE("performance"),
    OPERABILITY("operability"),
    MAINTAINABILITY("maintainability"),
    COST("cost"),
    ALL("all");

    private final String wireValue;

    ArchitectureReviewFocus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ArchitectureReviewFocus> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
