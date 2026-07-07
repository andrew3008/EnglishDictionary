package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum RefactorOutputFormat {
    REFACTOR_PLAN("refactor_plan"),
    MIGRATION_SLICES("migration_slices"),
    CHECKLIST("checklist"),
    ADR_OUTLINE("adr_outline");

    private final String wireValue;

    RefactorOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<RefactorOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
