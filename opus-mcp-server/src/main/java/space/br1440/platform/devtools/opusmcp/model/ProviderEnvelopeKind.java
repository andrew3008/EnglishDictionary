package space.br1440.platform.devtools.opusmcp.model;

/**
 * Identifies which supported provider response envelope shape supplied model text. Used in
 * metadata-only audit records; never logged together with raw response bodies.
 */
public enum ProviderEnvelopeKind {
    ANTHROPIC_MESSAGES("anthropic_messages"),
    GATEWAY_CONTENT_STRING("gateway_content_string"),
    OPENAI_CHAT_STRING("openai_chat_string"),
    OPENAI_CHAT_BLOCKS("openai_chat_blocks"),
    OPENAI_COMPLETION("openai_completion"),
    LEGACY_COMPLETION("legacy_completion"),
    ERROR_ENVELOPE("error_envelope"),
    NONE("none");

    private final String wireValue;

    ProviderEnvelopeKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
