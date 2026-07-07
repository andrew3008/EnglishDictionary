package space.br1440.platform.devtools.opusmcp.model;

/**
 * Internal diagnostic category for provider envelope parsing outcomes. Metadata-only; safe to log.
 */
public enum ProviderDiagnosticCategory {
    NONE("none"),
    INVALID_JSON("invalid_json"),
    ERROR_ENVELOPE("error_envelope"),
    NO_TEXT_FOUND("no_text_found");

    private final String wireValue;

    ProviderDiagnosticCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
