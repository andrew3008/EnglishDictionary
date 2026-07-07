package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code review_mdx_doc_with_opus}. */
public enum MdxReviewOutputFormat {
    STRUCTURED_REVIEW("structured_review"),
    CHECKLIST("checklist"),
    RISK_REVIEW("risk_review"),
    EDITORIAL_REVIEW("editorial_review"),
    PUBLISH_READINESS("publish_readiness");

    private final String wireValue;

    MdxReviewOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MdxReviewOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
