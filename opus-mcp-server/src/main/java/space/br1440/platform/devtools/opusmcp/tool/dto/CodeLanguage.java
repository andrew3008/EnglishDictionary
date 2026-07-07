package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

public enum CodeLanguage {
    JAVA("java"),
    GO("go"),
    KOTLIN("kotlin"),
    SQL("sql"),
    MDX("mdx"),
    GRADLE("gradle"),
    OTHER("other");

    private final String wireValue;

    CodeLanguage(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<CodeLanguage> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
