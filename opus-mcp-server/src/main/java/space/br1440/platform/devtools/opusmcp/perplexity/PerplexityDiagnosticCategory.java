package space.br1440.platform.devtools.opusmcp.perplexity;

/**
 * Phase 8A: operator-facing classification of a Perplexity provider smoke result. Diagnostics only —
 * never used by the MCP tools at runtime. Kept separate from the Anthropic/Opus
 * {@link space.br1440.platform.devtools.opusmcp.smoke.ProviderDiagnosticCategory} so the spike stays
 * isolated and can express OpenAI-compatible distinctions (e.g. {@code MODEL_NOT_FOUND}).
 *
 * <p>The category is a best-effort hint and does not claim an exact root cause.
 */
public enum PerplexityDiagnosticCategory {
    OK,
    RESPONSE_PARSE_ERROR,
    AUTH_ERROR,
    REQUEST_SHAPE_ERROR,
    MODEL_NOT_FOUND,
    RATE_LIMIT_OR_QUOTA,
    NETWORK_ERROR,
    PROVIDER_DOWN,
    UNKNOWN_PROVIDER_ERROR
}
