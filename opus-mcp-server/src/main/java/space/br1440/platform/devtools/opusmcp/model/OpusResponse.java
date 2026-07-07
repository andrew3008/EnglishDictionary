package space.br1440.platform.devtools.opusmcp.model;

public record OpusResponse(
        String text,
        int inputTokenEstimate,
        int outputTokenEstimate,
        ProviderCallMetadata providerMetadata) {

    public OpusResponse(String text, int inputTokenEstimate, int outputTokenEstimate) {
        this(text, inputTokenEstimate, outputTokenEstimate, ProviderCallMetadata.empty());
    }
}
