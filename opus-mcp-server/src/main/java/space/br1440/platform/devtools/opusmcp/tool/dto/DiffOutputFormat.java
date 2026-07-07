package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum DiffOutputFormat {
    DIFF_EXPLANATION("diff_explanation"),
    RISK_REVIEW("risk_review"),
    CHECKLIST("checklist"),
    MERGE_REVIEW("merge_review");

    private final String wireValue;

    DiffOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DiffOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
