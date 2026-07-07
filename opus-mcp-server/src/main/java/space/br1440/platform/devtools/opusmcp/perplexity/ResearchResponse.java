package space.br1440.platform.devtools.opusmcp.perplexity;

/**
 * Provider-neutral successful research response. Carries only the extracted text plus token/model
 * metadata — never the raw provider body, headers, or the API key.
 */
public record ResearchResponse(
        String text,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {
}
