package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * A single proposed type in a {@code design_class_hierarchy_with_opus} result. {@code kind} is stored
 * as a normalized string (defensive parsing): unknown kinds fall back to {@code class} so a malformed
 * model line never breaks the whole response.
 */
public record ProposedType(
        String name,
        String kind,
        String packageName,
        String responsibility,
        List<String> publicApi,
        List<String> collaborators,
        List<String> notes) {

    public static final String DEFAULT_KIND = "class";
}
