package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code design_class_hierarchy_with_opus}. Reuses {@link GenerateCodeStatus} for the status
 * enum. Independent of the other tool output records so existing tool contracts are never affected.
 */
public record DesignClassHierarchyOutput(
        GenerateCodeStatus status,
        String summary,
        String designOverview,
        List<ProposedType> proposedTypes,
        List<TypeRelationship> relationships,
        List<String> packagePlan,
        List<String> implementationSlices,
        List<String> extensionPoints,
        List<String> designAlternatives,
        List<String> testsToAdd,
        List<String> risks,
        List<String> antiPatternsToAvoid,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static DesignClassHierarchyOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new DesignClassHierarchyOutput(
                status,
                summary,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
