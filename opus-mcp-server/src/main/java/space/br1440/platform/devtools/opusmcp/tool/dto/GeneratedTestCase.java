package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single structured generated-test-case proposal. Type and priority are stored as normalized
 * strings (defensive parsing): unknown types fall back to {@code other} and unknown priorities to
 * {@code MEDIUM} so a malformed model line never breaks the whole response.
 */
public record GeneratedTestCase(
        String name,
        String type,
        String purpose,
        String given,
        String when,
        String then,
        String priority) {

    public static final String DEFAULT_TYPE = "other";
    public static final String DEFAULT_PRIORITY = "MEDIUM";
}
