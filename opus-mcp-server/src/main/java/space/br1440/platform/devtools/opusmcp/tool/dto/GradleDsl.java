package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Gradle DSL for {@code review_gradle_build_with_opus}. */
public enum GradleDsl {
    GROOVY("groovy"),
    KOTLIN("kotlin"),
    MIXED("mixed"),
    UNKNOWN("unknown");

    private final String wireValue;

    GradleDsl(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<GradleDsl> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
