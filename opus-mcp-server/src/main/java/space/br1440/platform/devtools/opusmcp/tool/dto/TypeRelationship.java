package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single relationship between proposed types. {@code type} is stored as a normalized string
 * (defensive parsing): unknown relationship types fall back to {@code uses} so a malformed model line
 * never breaks the whole response.
 */
public record TypeRelationship(
        String from,
        String to,
        String type,
        String reason) {

    public static final String DEFAULT_TYPE = "uses";
}
