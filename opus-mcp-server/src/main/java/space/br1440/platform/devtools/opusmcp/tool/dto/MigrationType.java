package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Migration type for {@code generate_migration_plan_with_opus}. */
public enum MigrationType {
    FRAMEWORK_UPGRADE("framework_upgrade"),
    API_MIGRATION("api_migration"),
    DEPENDENCY_UPGRADE("dependency_upgrade"),
    ARCHITECTURE_MIGRATION("architecture_migration"),
    CONFIGURATION_MIGRATION("configuration_migration"),
    DOCUMENTATION_MIGRATION("documentation_migration"),
    TEST_MIGRATION("test_migration"),
    BUILD_MIGRATION("build_migration"),
    UNKNOWN("unknown");

    private final String wireValue;

    MigrationType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MigrationType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
