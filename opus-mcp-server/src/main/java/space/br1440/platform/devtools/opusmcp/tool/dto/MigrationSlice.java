package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * A single, ideally small and reversible migration slice. {@code risk} is stored as a normalized
 * string (defensive parsing): unknown values fall back to {@code MEDIUM} so a malformed model line
 * never breaks the whole response.
 */
public record MigrationSlice(
        String id,
        String title,
        String goal,
        List<String> changes,
        List<String> verification,
        String risk,
        String rollback) {

    public static final String DEFAULT_RISK = "MEDIUM";
}
