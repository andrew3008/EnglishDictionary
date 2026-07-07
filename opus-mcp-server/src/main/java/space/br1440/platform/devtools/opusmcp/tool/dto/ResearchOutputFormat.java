package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code research_with_perplexity}. */
public enum ResearchOutputFormat {
    BRIEF("brief"),
    REPORT("report"),
    DECISION_MEMO("decision_memo"),
    SOURCE_TABLE("source_table");

    private final String wireValue;

    ResearchOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ResearchOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
