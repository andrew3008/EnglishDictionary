package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum CoverageFocus {
    HAPPY_PATH("happy_path"),
    EDGE_CASES("edge_cases"),
    ERROR_HANDLING("error_handling"),
    CONCURRENCY("concurrency"),
    SECURITY("security"),
    PERFORMANCE("performance"),
    SERIALIZATION("serialization"),
    ALL("all");

    private final String wireValue;

    CoverageFocus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<CoverageFocus> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
