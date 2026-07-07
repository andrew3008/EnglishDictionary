package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum RefactorScope {
    METHOD("method"),
    CLASS("class"),
    MODULE("module"),
    MULTI_MODULE("multi_module"),
    DOCUMENTATION("documentation"),
    BUILD("build"),
    UNKNOWN("unknown");

    private final String wireValue;

    RefactorScope(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<RefactorScope> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
