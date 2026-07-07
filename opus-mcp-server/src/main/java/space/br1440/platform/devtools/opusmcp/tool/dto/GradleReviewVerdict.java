package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Verdict for {@code review_gradle_build_with_opus}, with defensive parsing. */
public enum GradleReviewVerdict {
    APPROVE("APPROVE"),
    APPROVE_WITH_CHANGES("APPROVE_WITH_CHANGES"),
    REQUEST_CHANGES("REQUEST_CHANGES"),
    NEEDS_MORE_CONTEXT("NEEDS_MORE_CONTEXT");

    private final String wireValue;

    GradleReviewVerdict(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<GradleReviewVerdict> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
