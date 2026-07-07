package space.br1440.platform.devtools.opusmcp.perplexity;

/**
 * Provider-neutral abstraction for a single read-only research call. Implementations send only the
 * supplied prompts (already built from explicit tool input) and return the model text plus safe
 * metadata. This keeps {@code research_with_perplexity} testable offline with a fake client and fully
 * isolated from the Anthropic/Opus client.
 */
public interface ResearchClient {

    ResearchResponse research(String systemPrompt, String userPrompt) throws ResearchClientException;
}
