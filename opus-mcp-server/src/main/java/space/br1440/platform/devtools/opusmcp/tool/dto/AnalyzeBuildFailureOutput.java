package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code analyze_build_failure_with_opus}. Reuses {@link GenerateCodeStatus} for the status
 * enum. Independent of the other tool output records so existing tool contracts are never affected.
 */
public record AnalyzeBuildFailureOutput(
        GenerateCodeStatus status,
        String summary,
        List<String> rootCauseHypotheses,
        String mostLikelyCause,
        List<String> evidence,
        List<FixOption> fixOptions,
        String minimalPatchSuggestion,
        List<String> testsToRerun,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static AnalyzeBuildFailureOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new AnalyzeBuildFailureOutput(
                status,
                summary,
                List.of(),
                "",
                List.of(),
                List.of(),
                "",
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
