package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Build-failure category for {@code analyze_build_failure_with_opus}. */
public enum FailureType {
    COMPILE("compile"),
    TEST("test"),
    GRADLE("gradle"),
    CHECKSTYLE("checkstyle"),
    SPOTBUGS("spotbugs"),
    STATIC_ANALYSIS("static_analysis"),
    RUNTIME("runtime"),
    UNKNOWN("unknown");

    private final String wireValue;

    FailureType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<FailureType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
