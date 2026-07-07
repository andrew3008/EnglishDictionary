package space.br1440.platform.devtools.opusmcp.tool.dto;

import space.br1440.platform.devtools.opusmcp.model.OpusClientException;

import java.util.List;

/**
 * Output of {@code review_tests_with_opus}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of the other tool output records so existing tool contracts are never affected.
 * {@code review} is preserved as a verbatim multi-line text block.
 */
public record ReviewTestsOutput(
        GenerateCodeStatus status,
        String summary,
        String verdict,
        String review,
        List<TestFinding> findings,
        List<String> coverageGaps,
        List<String> assertionIssues,
        List<String> flakinessRisks,
        List<String> mockingIssues,
        List<String> testDataIssues,
        List<String> integrationBoundaryIssues,
        List<String> maintainabilityIssues,
        List<String> suggestedTestCases,
        List<String> ciReadinessChecks,
        List<String> openQuestions,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static ReviewTestsOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new ReviewTestsOutput(
                status,
                summary,
                ProviderFailureSemantics.verdictForStatus(
                        status, TestReviewVerdict.NEEDS_MORE_CONTEXT.wireValue()),
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

    public static ReviewTestsOutput ofProviderFailure(
            GenerateCodeStatus status,
            String summary,
            OpusClientException.Reason reason,
            String requestId,
            String model) {
        return new ReviewTestsOutput(
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
                ProviderFailureSemantics.risks("test review", reason),
                ProviderFailureSemantics.safetyNotes(),
                List.of(),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
