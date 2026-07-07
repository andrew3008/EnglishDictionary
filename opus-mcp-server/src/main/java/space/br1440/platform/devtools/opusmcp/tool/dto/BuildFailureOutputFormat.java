package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code analyze_build_failure_with_opus}. */
public enum BuildFailureOutputFormat {
    DIAGNOSIS("diagnosis"),
    FIX_PLAN("fix_plan"),
    CHECKLIST("checklist"),
    ROOT_CAUSE_ANALYSIS("root_cause_analysis");

    private final String wireValue;

    BuildFailureOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<BuildFailureOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
