package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Target audience for {@code write_mdx_doc_with_opus}. */
public enum DocTargetAudience {
    PLATFORM_DEVELOPERS("platform_developers"),
    APPLICATION_DEVELOPERS("application_developers"),
    SRE("sre"),
    ARCHITECTS("architects"),
    MIXED("mixed");

    private final String wireValue;

    DocTargetAudience(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DocTargetAudience> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
