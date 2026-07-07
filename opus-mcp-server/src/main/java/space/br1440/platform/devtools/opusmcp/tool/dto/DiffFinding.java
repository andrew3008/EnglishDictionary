package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single structured diff-analysis finding. Severity and category are stored as normalized strings
 * (defensive parsing): unknown severities fall back to {@code LOW} and unknown categories to
 * {@code other} so a malformed model line never breaks the whole response.
 */
public record DiffFinding(
        String severity,
        String category,
        String title,
        String details,
        String recommendation) {

    public static final String DEFAULT_SEVERITY = "LOW";
    public static final String DEFAULT_CATEGORY = "other";
}
