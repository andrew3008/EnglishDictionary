package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single structured fix option for {@code analyze_build_failure_with_opus}. {@code risk} is stored
 * as a normalized string (defensive parsing): unknown values fall back to {@code MEDIUM} so a
 * malformed model line never breaks the whole response.
 */
public record FixOption(
        String title,
        String description,
        String risk,
        boolean requiresCodeChange,
        boolean requiresDependencyChange) {

    public static final String DEFAULT_RISK = "MEDIUM";
}
