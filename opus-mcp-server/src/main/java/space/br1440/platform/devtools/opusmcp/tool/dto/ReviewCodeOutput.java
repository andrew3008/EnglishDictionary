package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code review_code_with_opus}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of {@link GenerateCodeOutput} so the existing tool contract is never affected.
 */
public record ReviewCodeOutput(
        GenerateCodeStatus status,
        String summary,
        String review,
        List<ReviewFinding> findings,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        List<String> testsToRun,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static ReviewCodeOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new ReviewCodeOutput(
                status,
                summary,
                "",
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
