package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code generate_migration_plan_with_opus}. */
public enum MigrationOutputFormat {
    MIGRATION_SLICES("migration_slices"),
    CHECKLIST("checklist"),
    RISK_MATRIX("risk_matrix"),
    ROLLOUT_PLAN("rollout_plan"),
    DECISION_MEMO("decision_memo");

    private final String wireValue;

    MigrationOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MigrationOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
