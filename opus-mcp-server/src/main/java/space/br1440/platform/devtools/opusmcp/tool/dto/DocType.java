package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Documentation type for {@code write_mdx_doc_with_opus}. */
public enum DocType {
    LIBRARY_GUIDE("library_guide"),
    STARTER_GUIDE("starter_guide"),
    MIGRATION_GUIDE("migration_guide"),
    HOW_TO("how_to"),
    REFERENCE("reference"),
    ADR("adr"),
    RELEASE_NOTES("release_notes"),
    TROUBLESHOOTING("troubleshooting"),
    UNKNOWN("unknown");

    private final String wireValue;

    DocType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<DocType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
