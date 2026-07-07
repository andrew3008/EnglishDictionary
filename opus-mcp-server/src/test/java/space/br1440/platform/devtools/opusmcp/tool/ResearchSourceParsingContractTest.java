package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8D source-parsing contract: documents the best-effort behavior of
 * {@link ResearchWithPerplexityTool#parseSources(List)} for the SOURCES block shapes the tool must
 * tolerate. Parsing never throws; malformed rows never abort the rest. Offline only.
 */
class ResearchSourceParsingContractTest {

    private static List<ResearchSource> parse(String... lines) {
        return ResearchWithPerplexityTool.parseSources(List.of(lines));
    }

    @Test
    void markdownTableSources() {
        List<ResearchSource> sources = parse(
                "| Title | URL | Publisher | Date | Relevance |",
                "|-------|-----|-----------|------|-----------|",
                "| Spring Docs | https://spring.io | VMware | 2024 | official |");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).title()).isEqualTo("Spring Docs");
        assertThat(sources.get(0).url()).isEqualTo("https://spring.io");
        assertThat(sources.get(0).publisher()).isEqualTo("VMware");
    }

    @Test
    void bulletListKeyValueSources() {
        List<ResearchSource> sources = parse(
                "- title: Spring Docs",
                "  url: https://spring.io",
                "  publisher: VMware",
                "- title: Baeldung",
                "  url: https://baeldung.com");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).title()).isEqualTo("Spring Docs");
        assertThat(sources.get(1).title()).isEqualTo("Baeldung");
    }

    @Test
    void numberedSources() {
        List<ResearchSource> sources = parse(
                "1. Spring Reference \u2014 https://docs.spring.io \u2014 official",
                "2. Baeldung Guide \u2014 https://baeldung.com \u2014 tutorial");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).title()).isEqualTo("Spring Reference");
        assertThat(sources.get(0).url()).isEqualTo("https://docs.spring.io");
        assertThat(sources.get(1).url()).isEqualTo("https://baeldung.com");
    }

    @Test
    void sourceUrlPrefixLines() {
        List<ResearchSource> sources = parse(
                "Source: https://spring.io/projects/spring-framework",
                "Link: https://www.baeldung.com/spring");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).url()).isEqualTo("https://spring.io/projects/spring-framework");
        assertThat(sources.get(1).url()).isEqualTo("https://www.baeldung.com/spring");
    }

    @Test
    void titleUrlRelevanceDelimitedRow() {
        List<ResearchSource> sources = parse(
                "- Spring Docs \u2014 https://spring.io \u2014 official reference");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).title()).isEqualTo("Spring Docs");
        assertThat(sources.get(0).url()).isEqualTo("https://spring.io");
        assertThat(sources.get(0).relevance()).contains("official reference");
    }

    @Test
    void missingPublisherAndDateLeaveEmptyStringsNotNull() {
        List<ResearchSource> sources = parse(
                "- title: Minimal Source",
                "  url: https://example.com");
        assertThat(sources).hasSize(1);
        ResearchSource s = sources.get(0);
        assertThat(s.publisher()).isEmpty();
        assertThat(s.date()).isEmpty();
        assertThat(s.relevance()).isEmpty();
    }

    @Test
    void duplicateUrlsArePreservedWithoutCrashing() {
        // Contract: duplicates are preserved (no dedupe) to stay best-effort and lossless.
        List<ResearchSource> sources = parse(
                "- title: First",
                "  url: https://dup.example",
                "- title: Second",
                "  url: https://dup.example");
        assertThat(sources).hasSize(2);
        assertThat(sources).allSatisfy(s -> assertThat(s.url()).isEqualTo("https://dup.example"));
    }

    @Test
    void invalidUrlIsKeptAsTextInUrlFieldForKeyValueRows() {
        // Contract: an explicit "url:" value is preserved verbatim; the parser does not validate URLs.
        List<ResearchSource> sources = parse(
                "- title: Weird Source",
                "  url: not-a-real-url");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).url()).isEqualTo("not-a-real-url");
    }

    @Test
    void bareBulletWithoutUrlIsKeptAsTitle() {
        List<ResearchSource> sources = parse(
                "- A descriptive source with no URL and no delimiters");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).title()).isEqualTo("A descriptive source with no URL and no delimiters");
        assertThat(sources.get(0).url()).isEmpty();
    }

    @Test
    void malformedRowDoesNotAbortParsingOfValidOnes() {
        List<ResearchSource> sources = parse(
                "- title: Good",
                "  url: https://good.example",
                "- @@@ totally broken @@@",
                "- title: AlsoGood",
                "  url: https://also.example");
        assertThat(sources).anySatisfy(s -> assertThat(s.title()).isEqualTo("Good"));
        assertThat(sources).anySatisfy(s -> assertThat(s.title()).isEqualTo("AlsoGood"));
    }

    @Test
    void emptyOrNullInputYieldsEmptyList() {
        assertThat(ResearchWithPerplexityTool.parseSources(null)).isEmpty();
        assertThat(ResearchWithPerplexityTool.parseSources(List.of())).isEmpty();
    }
}
