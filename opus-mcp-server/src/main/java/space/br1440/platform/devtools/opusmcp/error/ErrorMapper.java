package space.br1440.platform.devtools.opusmcp.error;

import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;

public final class ErrorMapper {

    public GenerateCodeStatus mapHttpStatus(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return GenerateCodeStatus.MODEL_ERROR;
        }
        if (statusCode == 429) {
            return GenerateCodeStatus.BUDGET_EXCEEDED;
        }
        return GenerateCodeStatus.MODEL_ERROR;
    }

    public String safeMessageForHttpStatus(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> "Authentication or access denied for the configured endpoint";
            case 404 -> "Endpoint not found; verify OPUS_BASE_URL and /v1/messages path";
            case 400 -> "Provider rejected the request shape or parameters";
            case 429 -> "Rate limit exceeded for the configured endpoint";
            default -> statusCode >= 500
                    ? "Upstream provider error (HTTP " + statusCode + ")"
                    : "Provider returned HTTP " + statusCode;
        };
    }

    public String safeMessageForException(OpusClientException exception) {
        return switch (exception.reason()) {
            case MISSING_API_KEY -> "Missing OPUS_API_KEY environment variable";
            case MISSING_BASE_URL -> "Missing OPUS_BASE_URL environment variable";
            case MODEL_NOT_ALLOWED -> "Configured model is not allowlisted";
            case TIMEOUT -> "Request to the model provider timed out";
            case PARSE_ERROR -> "Could not parse the provider response";
            case NETWORK_ERROR -> "Network error while calling the model provider";
            case HTTP_ERROR -> safeMessageForHttpStatus(exception.httpStatus());
        };
    }
}
