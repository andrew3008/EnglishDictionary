package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum ReviewOutputFormat {
    STRUCTURED_REVIEW("structured_review"),
    MARKDOWN("markdown"),
    CHECKLIST("checklist");

    private final String wireValue;

    ReviewOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ReviewOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
