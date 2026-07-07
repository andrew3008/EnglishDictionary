package space.br1440.platform.devtools.opusmcp.model;

/**
 * Metadata-only provider call diagnostics. Never carries prompts, model text, API keys, or raw HTTP
 * bodies.
 */
public record ProviderCallMetadata(
        String providerRequestId,
        String envelopeKind,
        String diagnosticCategory,
        boolean providerCallAttempted) {

    public static ProviderCallMetadata empty() {
        return new ProviderCallMetadata("", ProviderEnvelopeKind.NONE.wireValue(),
                ProviderDiagnosticCategory.NONE.wireValue(), false);
    }

    public static ProviderCallMetadata notAttempted() {
        return empty();
    }

    public static ProviderCallMetadata attemptedWithoutEnvelope() {
        return new ProviderCallMetadata("", ProviderEnvelopeKind.NONE.wireValue(),
                ProviderDiagnosticCategory.NONE.wireValue(), true);
    }

    public static ProviderCallMetadata success(String providerRequestId, ProviderEnvelopeKind kind) {
        return new ProviderCallMetadata(
                nullToEmpty(providerRequestId),
                kind.wireValue(),
                ProviderDiagnosticCategory.NONE.wireValue(),
                true);
    }

    public static ProviderCallMetadata failure(
            String providerRequestId,
            ProviderEnvelopeKind kind,
            ProviderDiagnosticCategory diagnostic) {
        return new ProviderCallMetadata(
                nullToEmpty(providerRequestId),
                kind.wireValue(),
                diagnostic.wireValue(),
                true);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
