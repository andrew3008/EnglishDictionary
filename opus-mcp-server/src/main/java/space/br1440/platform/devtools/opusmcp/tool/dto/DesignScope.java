package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Design scope for {@code design_class_hierarchy_with_opus}. */
public enum DesignScope {
    PACKAGE("package"),
    MODULE("module"),
    STARTER("starter"),
    LIBRARY("library"),
    MULTI_MODULE("multi_module"),
    UNKNOWN("unknown");

    private final String wireValue;

    DesignScope(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DesignScope> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
