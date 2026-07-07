package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Scope for {@code generate_migration_plan_with_opus}. */
public enum MigrationScope {
    CLASS("class"),
    PACKAGE("package"),
    MODULE("module"),
    MULTI_MODULE("multi_module"),
    PLATFORM("platform"),
    LIBRARY("library"),
    STARTER("starter"),
    DOCUMENTATION("documentation"),
    BUILD("build"),
    UNKNOWN("unknown");

    private final String wireValue;

    MigrationScope(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MigrationScope> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
