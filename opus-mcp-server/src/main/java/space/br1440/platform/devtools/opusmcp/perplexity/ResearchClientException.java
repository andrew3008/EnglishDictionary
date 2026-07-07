package space.br1440.platform.devtools.opusmcp.perplexity;

/**
 * Provider-neutral research failure. Carries only a safe diagnostic category and (optional) HTTP
 * status — never the raw provider body or the API key. The message is a short, secret-free summary.
 */
public final class ResearchClientException extends Exception {

    private final PerplexityDiagnosticCategory category;
    private final int httpStatus;

    public ResearchClientException(PerplexityDiagnosticCategory category, int httpStatus, String message) {
        super(message);
        this.category = category == null ? PerplexityDiagnosticCategory.UNKNOWN_PROVIDER_ERROR : category;
        this.httpStatus = httpStatus;
    }

    public PerplexityDiagnosticCategory category() {
        return category;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
