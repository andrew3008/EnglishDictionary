package space.br1440.platform.devtools.opusmcp.model;

public record OpusRequest(
        String model,
        int maxTokens,
        String systemPrompt,
        String userPrompt) {
}
