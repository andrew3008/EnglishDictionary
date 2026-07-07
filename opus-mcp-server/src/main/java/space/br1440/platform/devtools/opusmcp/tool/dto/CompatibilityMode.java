package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum CompatibilityMode {
    PRESERVE_BEHAVIOR("preserve_behavior"),
    ALLOW_BEHAVIOR_CHANGE("allow_behavior_change"),
    UNKNOWN("unknown");

    private final String wireValue;

    CompatibilityMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<CompatibilityMode> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
