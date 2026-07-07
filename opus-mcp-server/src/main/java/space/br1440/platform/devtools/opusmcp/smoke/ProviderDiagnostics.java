package space.br1440.platform.devtools.opusmcp.smoke;

import space.br1440.platform.devtools.opusmcp.security.Masking;

/**
 * Diagnostics-only helpers to classify a provider smoke result and produce a safe, redacted error
 * body preview. Pure functions; no network, no MCP runtime impact.
 */
public final class ProviderDiagnostics {

    /** Default cap for error-body previews. */
    public static final int DEFAULT_PREVIEW_MAX = 1000;
    public static final String EMPTY_BODY = "<empty>";
    public static final String TRUNCATED_SUFFIX = "...[truncated]";

    private ProviderDiagnostics() {
    }

    /**
     * Classifies a completed HTTP exchange. For 2xx, {@code parseOk} distinguishes {@code OK} from
     * {@code RESPONSE_PARSE_ERROR}. {@code body} is only inspected to refine a 404.
     */
    public static ProviderDiagnosticCategory classify(int statusCode, boolean parseOk, String body) {
        if (statusCode >= 200 && statusCode < 300) {
            return parseOk ? ProviderDiagnosticCategory.OK
                    : ProviderDiagnosticCategory.RESPONSE_PARSE_ERROR;
        }
        return switch (statusCode) {
            case 401, 403 -> ProviderDiagnosticCategory.AUTH_ERROR;
            case 400 -> ProviderDiagnosticCategory.REQUEST_SHAPE_ERROR;
            case 404 -> mentionsModel(body)
                    ? ProviderDiagnosticCategory.MODEL_ROUTE_DOWN
                    : ProviderDiagnosticCategory.REQUEST_SHAPE_ERROR;
            case 408 -> ProviderDiagnosticCategory.NETWORK_ERROR;
            case 429 -> ProviderDiagnosticCategory.RATE_LIMIT_OR_QUOTA;
            case 500, 502, 503, 504 -> ProviderDiagnosticCategory.PROVIDER_DOWN;
            default -> statusCode >= 500
                    ? ProviderDiagnosticCategory.PROVIDER_DOWN
                    : ProviderDiagnosticCategory.UNKNOWN_PROVIDER_ERROR;
        };
    }

    /** Category for a transport-level failure (timeout, connection reset, DNS, etc.). */
    public static ProviderDiagnosticCategory classifyNetworkFailure() {
        return ProviderDiagnosticCategory.NETWORK_ERROR;
    }

    private static boolean mentionsModel(String body) {
        return body != null && body.toLowerCase().contains("model");
    }

    /** Safe, redacted, single-line, length-capped preview of an error body. */
    public static String previewBody(String body) {
        return previewBody(body, DEFAULT_PREVIEW_MAX);
    }

    public static String previewBody(String body, int maxChars) {
        if (body == null || body.isBlank()) {
            return EMPTY_BODY;
        }
        // Mask first so secrets are removed before any truncation boundary.
        String masked = Masking.mask(body);
        String collapsed = masked.replaceAll("\\s+", " ").strip();
        if (collapsed.isEmpty()) {
            return EMPTY_BODY;
        }
        int cap = Math.max(1, maxChars);
        if (collapsed.length() > cap) {
            return collapsed.substring(0, cap) + TRUNCATED_SUFFIX;
        }
        return collapsed;
    }

    /** Best-effort HTTP reason phrase for common codes; falls back to {@code "HTTP <code>"}. */
    public static String statusDescription(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 408 -> "Request Timeout";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "HTTP " + statusCode;
        };
    }
}
