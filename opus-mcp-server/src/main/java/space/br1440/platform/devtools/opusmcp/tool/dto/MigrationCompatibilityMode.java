package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Compatibility mode for {@code generate_migration_plan_with_opus}. */
public enum MigrationCompatibilityMode {
    PRESERVE_API("preserve_api"),
    PRESERVE_BEHAVIOR("preserve_behavior"),
    ALLOW_BREAKING("allow_breaking"),
    UNKNOWN("unknown");

    private final String wireValue;

    MigrationCompatibilityMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MigrationCompatibilityMode> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
