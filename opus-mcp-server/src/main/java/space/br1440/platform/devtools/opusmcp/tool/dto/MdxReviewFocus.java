package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Review focus for {@code review_mdx_doc_with_opus}. */
public enum MdxReviewFocus {
    ACCURACY("accuracy"),
    STYLE("style"),
    STRUCTURE("structure"),
    EXAMPLES("examples"),
    MDX_VALIDITY("mdx_validity"),
    CLAIMS("claims"),
    NAVIGATION("navigation"),
    ACCESSIBILITY("accessibility"),
    ALL("all");

    private final String wireValue;

    MdxReviewFocus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MdxReviewFocus> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
