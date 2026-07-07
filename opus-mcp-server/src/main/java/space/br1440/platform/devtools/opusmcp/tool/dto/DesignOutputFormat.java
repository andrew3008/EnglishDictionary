package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code design_class_hierarchy_with_opus}. */
public enum DesignOutputFormat {
    CLASS_DIAGRAM("class_diagram"),
    DESIGN_PROPOSAL("design_proposal"),
    IMPLEMENTATION_SLICES("implementation_slices"),
    ADR_OUTLINE("adr_outline"),
    CHECKLIST("checklist");

    private final String wireValue;

    DesignOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DesignOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
