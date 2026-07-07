package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Source preference for {@code research_with_perplexity}. */
public enum ResearchSourcePreference {
    OFFICIAL_DOCS("official_docs"),
    INDUSTRY_BEST_PRACTICES("industry_best_practices"),
    ACADEMIC("academic"),
    MIXED("mixed");

    private final String wireValue;

    ResearchSourcePreference(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ResearchSourcePreference> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
