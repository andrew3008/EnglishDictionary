package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code generate_tests_with_opus}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of {@link GenerateCodeOutput} and {@link ReviewCodeOutput} so existing tool contracts
 * are never affected.
 */
public record GenerateTestsOutput(
        GenerateCodeStatus status,
        String summary,
        String testPlan,
        String testCode,
        List<GeneratedTestCase> testCases,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        List<String> testsToRun,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static GenerateTestsOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new GenerateTestsOutput(
                status,
                summary,
                "",
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
