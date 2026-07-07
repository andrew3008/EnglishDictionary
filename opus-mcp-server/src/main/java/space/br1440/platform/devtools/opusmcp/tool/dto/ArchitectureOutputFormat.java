package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code review_architecture_with_opus}. */
public enum ArchitectureOutputFormat {
    STRUCTURED_REVIEW("structured_review"),
    RISK_MATRIX("risk_matrix"),
    DECISION_MEMO("decision_memo"),
    ADR_REVIEW("adr_review"),
    CHECKLIST("checklist");

    private final String wireValue;

    ArchitectureOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ArchitectureOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
