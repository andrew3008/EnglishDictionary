package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code generate_migration_plan_with_opus}. Reuses {@link GenerateCodeStatus} for the
 * status enum. Independent of the other tool output records so existing tool contracts are never
 * affected. {@code migrationOverview} is preserved as a verbatim multi-line text block.
 */
public record GenerateMigrationPlanOutput(
        GenerateCodeStatus status,
        String summary,
        String migrationOverview,
        List<MigrationSlice> migrationSlices,
        List<String> compatibilityNotes,
        List<String> breakingRisks,
        List<String> dependencyChanges,
        List<String> configurationChanges,
        List<String> testPlan,
        List<String> observabilityChecks,
        List<String> rolloutPlan,
        List<String> rollbackPlan,
        List<String> docsUpdates,
        List<String> openQuestions,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static GenerateMigrationPlanOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new GenerateMigrationPlanOutput(
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
