package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single architecture-review finding. {@code severity} and {@code category} are stored as normalized
 * strings (defensive parsing): unknown values fall back to {@code MEDIUM} / {@code other} so a
 * malformed model line never breaks the whole response.
 */
public record ArchitectureFinding(
        String severity,
        String category,
        String title,
        String details,
        String recommendation) {

    public static final String DEFAULT_SEVERITY = "MEDIUM";
    public static final String DEFAULT_CATEGORY = "other";
}
