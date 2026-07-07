package space.br1440.platform.devtools.opusmcp.tool.dto;

/**
 * A single source citation parsed from a {@code research_with_perplexity} response. All fields are
 * plain strings; parsing is defensive and a malformed row never aborts the rest of the response.
 */
public record ResearchSource(
        String title,
        String url,
        String publisher,
        String date,
        String relevance) {
}
