package space.br1440.platform.devtools.opusmcp.tool.dto;

import space.br1440.platform.devtools.opusmcp.model.OpusClientException;

import java.util.List;

/**
 * Output of {@code review_architecture_with_opus}. Reuses {@link GenerateCodeStatus} for the status
 * enum. Independent of the other tool output records so existing tool contracts are never affected.
 */
public record ReviewArchitectureOutput(
        GenerateCodeStatus status,
        String summary,
        String verdict,
        String review,
        List<ArchitectureFinding> findings,
        List<ArchitectureRisk> riskMatrix,
        List<String> tradeOffs,
        List<String> alternatives,
        List<String> openQuestions,
        List<String> testsToAdd,
        List<String> observabilityChecks,
        List<String> rolloutNotes,
        List<String> rollbackNotes,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static ReviewArchitectureOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new ReviewArchitectureOutput(
                status,
                summary,
                ProviderFailureSemantics.verdictForStatus(
                        status, ArchitectureVerdict.NEEDS_MORE_CONTEXT.wireValue()),
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
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }

    public static ReviewArchitectureOutput ofProviderFailure(
            GenerateCodeStatus status,
            String summary,
            OpusClientException.Reason reason,
            String requestId,
            String model) {
        return new ReviewArchitectureOutput(
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
                ProviderFailureSemantics.risks("architecture review", reason),
                ProviderFailureSemantics.safetyNotes(),
                List.of(),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
