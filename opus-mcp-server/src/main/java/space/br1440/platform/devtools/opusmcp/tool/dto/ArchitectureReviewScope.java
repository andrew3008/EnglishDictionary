package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Architecture scope for {@code review_architecture_with_opus}. */
public enum ArchitectureReviewScope {
    CLASS("class"),
    PACKAGE("package"),
    MODULE("module"),
    MULTI_MODULE("multi_module"),
    PLATFORM("platform"),
    LIBRARY("library"),
    STARTER("starter"),
    UNKNOWN("unknown");

    private final String wireValue;

    ArchitectureReviewScope(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ArchitectureReviewScope> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
