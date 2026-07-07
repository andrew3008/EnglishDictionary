package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Output format for {@code write_mdx_doc_with_opus}. */
public enum MdxOutputFormat {
    MDX_PAGE("mdx_page"),
    MDX_SECTION("mdx_section"),
    OUTLINE("outline"),
    FRONTMATTER_PLUS_BODY("frontmatter_plus_body"),
    REVIEWABLE_DRAFT("reviewable_draft");

    private final String wireValue;

    MdxOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MdxOutputFormat> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
