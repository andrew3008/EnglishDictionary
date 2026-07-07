package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum DiffFormat {
    UNIFIED_DIFF("unified_diff"),
    GIT_DIFF("git_diff"),
    PATCH("patch"),
    PLAIN_TEXT("plain_text"),
    UNKNOWN("unknown");

    private final String wireValue;

    DiffFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DiffFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
