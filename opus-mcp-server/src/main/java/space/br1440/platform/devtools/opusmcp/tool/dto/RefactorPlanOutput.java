package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code refactor_plan_with_opus}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of the other tool output records so existing tool contracts are never affected.
 */
public record RefactorPlanOutput(
        GenerateCodeStatus status,
        String summary,
        String plan,
        List<RefactorStep> steps,
        List<String> affectedAreas,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        List<String> testsToRun,
        String rollbackPlan,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static RefactorPlanOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new RefactorPlanOutput(
                status,
                summary,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
