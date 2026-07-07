package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Architecture style for {@code design_class_hierarchy_with_opus}. */
public enum ArchitectureStyle {
    CLEAN_ARCHITECTURE("clean_architecture"),
    HEXAGONAL("hexagonal"),
    LAYERED("layered"),
    SPRING_BOOT_STARTER("spring_boot_starter"),
    PLUGIN("plugin"),
    INTERCEPTOR_PIPELINE("interceptor_pipeline"),
    DOMAIN_MODEL("domain_model"),
    UNKNOWN("unknown");

    private final String wireValue;

    ArchitectureStyle(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ArchitectureStyle> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
