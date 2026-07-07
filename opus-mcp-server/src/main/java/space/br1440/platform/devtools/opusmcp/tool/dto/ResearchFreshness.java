package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Freshness preference for {@code research_with_perplexity}. */
public enum ResearchFreshness {
    LATEST("latest"),
    LAST_12_MONTHS("last_12_months"),
    STABLE("stable");

    private final String wireValue;

    ResearchFreshness(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ResearchFreshness> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
