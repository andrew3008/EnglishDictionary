package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/**
 * Verdict for an MDX documentation review. Defensive parsing: an unrecognized verdict normalizes to
 * {@link #NEEDS_MORE_CONTEXT} so a malformed model line never breaks the response.
 */
public enum MdxReviewVerdict {
    APPROVE("APPROVE"),
    APPROVE_WITH_CHANGES("APPROVE_WITH_CHANGES"),
    REQUEST_CHANGES("REQUEST_CHANGES"),
    NEEDS_MORE_CONTEXT("NEEDS_MORE_CONTEXT");

    private final String wireValue;

    MdxReviewVerdict(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<MdxReviewVerdict> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String norm = value.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equals(norm))
                .findFirst();
    }
}
