package space.br1440.platform.devtools.opusmcp.tool.dto;

import space.br1440.platform.devtools.opusmcp.model.OpusClientException;

import java.util.List;

/**
 * Shared semantics for tool responses when the provider call fails before a domain review/plan
 * is produced. Keeps domain verdict fields empty and adds explicit operator-facing notes so
 * technical failures are not confused with model-requested context.
 */
public final class ProviderFailureSemantics {

    private ProviderFailureSemantics() {
    }

    public static String verdictForStatus(GenerateCodeStatus status, String needsMoreContextWireValue) {
        return status == GenerateCodeStatus.NEEDS_MORE_CONTEXT ? needsMoreContextWireValue : "";
    }

    public static List<String> risks(String reviewKind, OpusClientException.Reason reason) {
        String detail = switch (reason) {
            case TIMEOUT -> "Provider timeout; no " + reviewKind + " was produced";
            case NETWORK_ERROR -> "Network error; no " + reviewKind + " was produced";
            case PARSE_ERROR -> "Provider response could not be parsed; no " + reviewKind + " was produced";
            case HTTP_ERROR -> "Provider HTTP error; no " + reviewKind + " was produced";
            default -> "Provider call failed; no " + reviewKind + " was produced";
        };
        return List.of(detail);
    }

    public static List<String> safetyNotes() {
        return List.of(
                "Technical provider error. verdict is empty and does not reflect a domain review conclusion.",
                "Retry the same curated input or reduce prompt size if timeouts repeat.");
    }
}
