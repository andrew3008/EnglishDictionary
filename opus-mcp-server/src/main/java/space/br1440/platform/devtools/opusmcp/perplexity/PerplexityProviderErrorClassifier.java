package space.br1440.platform.devtools.opusmcp.perplexity;

/**
 * Phase 8A: pure, diagnostics-only classifier for Perplexity (OpenAI-compatible) HTTP outcomes.
 * No network, no MCP runtime impact. Maps HTTP status codes to a safe operator category.
 *
 * <p>OpenAI-compatible providers typically signal an unknown/unsupported model with {@code 400} (and
 * a body mentioning the model) or {@code 404}; both are mapped to {@link
 * PerplexityDiagnosticCategory#MODEL_NOT_FOUND} when the body mentions a model, otherwise to a
 * request-shape/route error.
 */
public final class PerplexityProviderErrorClassifier {

    private PerplexityProviderErrorClassifier() {
    }

    /**
     * Classifies a completed HTTP exchange. For 2xx, {@code parseOk} distinguishes {@code OK} from
     * {@code RESPONSE_PARSE_ERROR}. {@code body} is only inspected to refine model-related errors.
     */
    public static PerplexityDiagnosticCategory classify(int statusCode, boolean parseOk, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return parseOk ? PerplexityDiagnosticCategory.OK
                    : PerplexityDiagnosticCategory.RESPONSE_PARSE_ERROR;
        }
        return switch (statusCode) {
            case 401, 403 -> PerplexityDiagnosticCategory.AUTH_ERROR;
            case 400 -> mentionsModel(body)
                    ? PerplexityDiagnosticCategory.MODEL_NOT_FOUND
                    : PerplexityDiagnosticCategory.REQUEST_SHAPE_ERROR;
            case 404 -> mentionsModel(body)
                    ? PerplexityDiagnosticCategory.MODEL_NOT_FOUND
                    : PerplexityDiagnosticCategory.REQUEST_SHAPE_ERROR;
            case 408 -> PerplexityDiagnosticCategory.NETWORK_ERROR;
            case 429 -> PerplexityDiagnosticCategory.RATE_LIMIT_OR_QUOTA;
            case 500, 502, 503, 504 -> PerplexityDiagnosticCategory.PROVIDER_DOWN;
            default -> statusCode >= 500
                    ? PerplexityDiagnosticCategory.PROVIDER_DOWN
                    : PerplexityDiagnosticCategory.UNKNOWN_PROVIDER_ERROR;
        };
    }

    /** Category for a transport-level failure (timeout, connection reset, DNS, etc.). */
    public static PerplexityDiagnosticCategory classifyNetworkFailure() {
        return PerplexityDiagnosticCategory.NETWORK_ERROR;
    }

    private static boolean mentionsModel(String body) {
        return body != null && body.toLowerCase().contains("model");
    }
}
