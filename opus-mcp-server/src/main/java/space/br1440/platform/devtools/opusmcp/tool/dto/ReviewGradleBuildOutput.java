package space.br1440.platform.devtools.opusmcp.tool.dto;

import space.br1440.platform.devtools.opusmcp.model.OpusClientException;

import java.util.List;

/**
 * Output of {@code review_gradle_build_with_opus}. Reuses {@link GenerateCodeStatus} for the status
 * enum. Independent of the other tool output records so existing tool contracts are never affected.
 * {@code review} is preserved as a verbatim multi-line text block.
 */
public record ReviewGradleBuildOutput(
        GenerateCodeStatus status,
        String summary,
        String verdict,
        String review,
        List<GradleFinding> findings,
        List<String> configurationCacheIssues,
        List<String> dependencyIssues,
        List<String> pluginIssues,
        List<String> taskGraphIssues,
        List<String> multiModuleIssues,
        List<String> testSetupIssues,
        List<String> publishingIssues,
        List<String> performanceIssues,
        List<String> securityIssues,
        List<String> compatibilityRisks,
        List<String> recommendedChecks,
        List<String> suggestedChanges,
        List<String> openQuestions,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static ReviewGradleBuildOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new ReviewGradleBuildOutput(
                status,
                summary,
                ProviderFailureSemantics.verdictForStatus(
                        status, GradleReviewVerdict.NEEDS_MORE_CONTEXT.wireValue()),
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
                List.of(),
                List.of(),
                List.of(),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }

    public static ReviewGradleBuildOutput ofProviderFailure(
            GenerateCodeStatus status,
            String summary,
            OpusClientException.Reason reason,
            String requestId,
            String model) {
        return new ReviewGradleBuildOutput(
                status,
                summary,
                "",
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
                ProviderFailureSemantics.risks("Gradle build review", reason),
                ProviderFailureSemantics.safetyNotes(),
                List.of(),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
