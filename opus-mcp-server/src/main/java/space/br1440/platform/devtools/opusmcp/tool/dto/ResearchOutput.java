package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code research_with_perplexity}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of the other tool output records so existing tool contracts are never affected.
 */
public record ResearchOutput(
        GenerateCodeStatus status,
        String summary,
        String answer,
        List<String> keyFindings,
        List<ResearchSource> sources,
        List<String> recommendations,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        List<String> followUpQuestions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    /** Status-only result (no provider answer/sources). */
    public static ResearchOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new ResearchOutput(
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
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }

    /**
     * Safe result returned when the Perplexity provider is not configured (no API key). No provider
     * call is made. Mirrors the contract documented for the tool.
     */
    public static ResearchOutput providerNotConfigured(String requestId, String model) {
        return new ResearchOutput(
                GenerateCodeStatus.MODEL_ERROR,
                "Perplexity provider is not configured: PERPLEXITY_API_KEY is not set.",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("No provider call was made."),
                List.of(),
                List.of("Set PERPLEXITY_API_KEY and rerun the smoke script to verify live research."),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
