package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code explain_diff_with_opus}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of the other tool output records so existing tool contracts are never affected.
 */
public record ExplainDiffOutput(
        GenerateCodeStatus status,
        String summary,
        String explanation,
        List<String> changedFiles,
        List<String> behaviorChanges,
        List<String> risks,
        List<DiffFinding> findings,
        List<String> testsToRun,
        List<String> safetyNotes,
        List<String> assumptions,
        MergeRecommendation mergeRecommendation,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static ExplainDiffOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new ExplainDiffOutput(
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
                MergeRecommendation.NEEDS_MORE_CONTEXT,
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
