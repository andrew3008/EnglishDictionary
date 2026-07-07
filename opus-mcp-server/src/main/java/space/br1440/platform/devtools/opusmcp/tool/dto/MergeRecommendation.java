package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;

/**
 * Merge recommendation for an explained diff. Parsed defensively from the model's
 * {@code MERGE_RECOMMENDATION} section; unrecognized values fall back to {@link #NEEDS_MORE_CONTEXT}.
 */
public enum MergeRecommendation {
    APPROVE,
    APPROVE_WITH_CHANGES,
    REQUEST_CHANGES,
    NEEDS_MORE_CONTEXT;

    public static MergeRecommendation fromTextOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return NEEDS_MORE_CONTEXT;
        }
        String normalized = value.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return Arrays.stream(values())
                .filter(v -> v.name().equals(normalized))
                .findFirst()
                .orElse(NEEDS_MORE_CONTEXT);
    }
}
