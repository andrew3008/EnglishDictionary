package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * Validated input for {@code research_with_perplexity}. Independent of the Opus tool inputs so
 * existing tool contracts are never affected. {@code context}/{@code constraints} are optional and
 * normalized to empty strings when absent.
 */
public record ResearchInput(
        String task,
        String researchQuestion,
        String context,
        String constraints,
        ResearchSourcePreference sourcePreference,
        ResearchFreshness freshness,
        ResearchDepth depth,
        ResearchOutputFormat outputFormat,
        RiskLevel riskLevel) {
}
