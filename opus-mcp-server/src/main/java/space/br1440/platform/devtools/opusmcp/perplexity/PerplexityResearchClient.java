package space.br1440.platform.devtools.opusmcp.perplexity;

import space.br1440.platform.devtools.opusmcp.model.AnthropicHttpOpusClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Production {@link ResearchClient} backed by the isolated {@link PerplexityHttpClient}
 * (OpenAI-compatible {@code /chat/completions}). Combines the system + user prompts into a single
 * request, maps a failed {@link PerplexityHttpClient.PerplexityResult} to a safe
 * {@link ResearchClientException}, and estimates tokens without echoing any provider body.
 *
 * <p>This never reads files, runs commands, or applies patches; it only performs the research HTTP
 * call when invoked by the tool (which has already verified the API key is present).
 */
public final class PerplexityResearchClient implements ResearchClient {

    /** Output-token budget for research answers (larger than the connectivity spike). */
    public static final int RESEARCH_MAX_TOKENS = 2048;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final PerplexityConfig config;
    private final PerplexityHttpClient httpClientSpike;
    private final HttpClient httpClient;

    public PerplexityResearchClient(PerplexityConfig config) {
        this(config, new PerplexityHttpClient(),
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    PerplexityResearchClient(PerplexityConfig config, PerplexityHttpClient httpClientSpike,
            HttpClient httpClient) {
        this.config = config;
        this.httpClientSpike = httpClientSpike;
        this.httpClient = httpClient;
    }

    @Override
    public ResearchResponse research(String systemPrompt, String userPrompt)
            throws ResearchClientException {
        String combined = (systemPrompt == null ? "" : systemPrompt)
                + "\n\n" + (userPrompt == null ? "" : userPrompt);
        PerplexityHttpClient.PerplexityResult result =
                httpClientSpike.run(config, combined, RESEARCH_MAX_TOKENS, httpClient);
        if (!result.ok()) {
            throw new ResearchClientException(result.diagnosticCategory(), result.statusCode(),
                    result.message());
        }
        int inputTokens = AnthropicHttpOpusClient.estimateTokens(combined);
        int outputTokens = AnthropicHttpOpusClient.estimateTokens(result.extractedText());
        return new ResearchResponse(result.extractedText(), inputTokens, outputTokens,
                result.model(), result.requestId());
    }
}
