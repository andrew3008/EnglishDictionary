package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum OutputFormat {
    UNIFIED_DIFF("unified_diff"),
    FULL_FILE("full_file"),
    CODE_BLOCK("code_block"),
    IMPLEMENTATION_PLAN("implementation_plan"),
    REVIEW("review");

    private final String wireValue;

    OutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<OutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
