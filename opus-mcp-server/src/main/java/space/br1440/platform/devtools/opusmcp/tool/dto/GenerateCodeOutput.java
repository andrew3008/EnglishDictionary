package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

public record GenerateCodeOutput(
        GenerateCodeStatus status,
        String summary,
        String result,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        List<String> testsToRun,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static GenerateCodeOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new GenerateCodeOutput(
                status,
                summary,
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
