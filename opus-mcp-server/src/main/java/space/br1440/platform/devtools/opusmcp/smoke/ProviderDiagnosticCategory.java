package space.br1440.platform.devtools.opusmcp.smoke;

/**
 * Operator-facing classification of a provider/endpoint smoke result. Diagnostics only — never used
 * by the MCP tools at runtime. The category is a best-effort hint ("provider/gateway/upstream") and
 * does not claim an exact root cause.
 */
public enum ProviderDiagnosticCategory {
    OK,
    RESPONSE_PARSE_ERROR,
    AUTH_ERROR,
    REQUEST_SHAPE_ERROR,
    MODEL_ROUTE_DOWN,
    RATE_LIMIT_OR_QUOTA,
    NETWORK_ERROR,
    PROVIDER_DOWN,
    UNKNOWN_PROVIDER_ERROR
}
