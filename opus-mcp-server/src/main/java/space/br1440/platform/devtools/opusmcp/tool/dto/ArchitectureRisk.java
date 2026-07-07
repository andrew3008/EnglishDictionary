package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single row of the architecture review risk matrix. {@code likelihood} and {@code impact} are
 * stored as normalized strings (defensive parsing): unknown values fall back to {@code MEDIUM} so a
 * malformed model line never breaks the whole response.
 */
public record ArchitectureRisk(
        String risk,
        String likelihood,
        String impact,
        String mitigation) {

    public static final String DEFAULT_LEVEL = "MEDIUM";
}
