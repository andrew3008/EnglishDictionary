package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Compatibility mode for {@code review_architecture_with_opus}. */
public enum ArchitectureCompatibilityMode {
    PRESERVE_API("preserve_api"),
    ALLOW_BREAKING("allow_breaking"),
    UNKNOWN("unknown");

    private final String wireValue;

    ArchitectureCompatibilityMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ArchitectureCompatibilityMode> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
