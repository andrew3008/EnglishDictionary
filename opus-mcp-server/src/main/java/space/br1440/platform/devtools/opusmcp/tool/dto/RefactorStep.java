package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single structured refactoring-plan step. Risk and category are stored as normalized strings
 * (defensive parsing): unknown risks fall back to {@code MEDIUM} and unknown categories to
 * {@code other} so a malformed model line never breaks the whole response.
 */
public record RefactorStep(
        String id,
        String title,
        String description,
        String risk,
        String category,
        boolean requiresBehaviorChange,
        String verification) {

    public static final String DEFAULT_RISK = "MEDIUM";
    public static final String DEFAULT_CATEGORY = "other";
}
