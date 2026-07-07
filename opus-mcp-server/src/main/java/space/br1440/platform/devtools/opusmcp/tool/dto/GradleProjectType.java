package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Project type for {@code review_gradle_build_with_opus}. */
public enum GradleProjectType {
    JAVA_LIBRARY("java_library"),
    SPRING_BOOT_SERVICE("spring_boot_service"),
    SPRING_BOOT_STARTER("spring_boot_starter"),
    GRADLE_PLUGIN("gradle_plugin"),
    MULTI_MODULE_PLATFORM("multi_module_platform"),
    DOCUMENTATION("documentation"),
    UNKNOWN("unknown");

    private final String wireValue;

    GradleProjectType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<GradleProjectType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
