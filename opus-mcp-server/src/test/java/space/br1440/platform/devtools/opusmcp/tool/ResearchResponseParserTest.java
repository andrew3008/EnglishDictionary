package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityConfig;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchClient;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchResponse;
import space.br1440.platform.devtools.opusmcp.prompt.ResearchPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8C offline parser-robustness tests: feed deterministic Perplexity-like response text through a
 * fake {@link ResearchClient} (no network, no API key) and assert the parser is defensive and
 * format-tolerant.
 */
class ResearchResponseParserTest {

    private static final class FakeResearchClient implements ResearchClient {
        final String text;

        FakeResearchClient(String text) {
            this.text = text;
        }

        @Override
        public ResearchResponse research(String systemPrompt, String userPrompt) {
            return new ResearchResponse(text, 10, 20, "sonar-deep-research", "rid");
        }
    }

    private ResearchWithPerplexityTool tool(String responseText, int maxOutputChars) {
        return new ResearchWithPerplexityTool(
                new PerplexityConfig(Map.of(PerplexityConfig.ENV_API_KEY, "k")),
                new FakeResearchClient(responseText), new ResearchPromptBuilder(),
                new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, maxOutputChars),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Public research");
        m.put("researchQuestion", "What is the Spring Framework?");
        m.put("context", "");
        m.put("constraints", "");
        m.put("sourcePreference", "official_docs");
        m.put("freshness", "latest");
        m.put("depth", "standard");
        m.put("outputFormat", "report");
        m.put("riskLevel", "low");
        return m;
    }

    private ResearchOutput run(String responseText) {
        return tool(responseText, 50_000).handle(args());
    }

    @Test
    void normalReportParsesAllSections() {
        String text = """
                SUMMARY:
                Spring is a JVM application framework.

                ANSWER:
                Spring Framework provides IoC and DI.

                KEY_FINDINGS:
                - Dependency injection
                - Modular

                SOURCES:
                - title: Spring Docs
                  url: https://spring.io
                  publisher: VMware
                  date: 2024
                  relevance: official

                RECOMMENDATIONS:
                - Use Spring Boot

                RISKS:
                - Version drift
                """;
        ResearchOutput out = run(text);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.answer()).contains("IoC and DI");
        assertThat(out.keyFindings()).containsExactly("Dependency injection", "Modular");
        assertThat(out.sources()).hasSize(1);
        assertThat(out.recommendations()).containsExactly("Use Spring Boot");
        assertThat(out.risks()).containsExactly("Version drift");
    }

    @Test
    void briefMinimalSectionsParsed() {
        ResearchOutput out = run("ANSWER:\nShort answer only.\n");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.answer()).isEqualTo("Short answer only.");
        assertThat(out.sources()).isEmpty();
        assertThat(out.keyFindings()).isEmpty();
    }

    @Test
    void markdownSourceTableParsed() {
        String text = """
                ANSWER:
                See sources.

                SOURCES:
                | Title | URL | Publisher | Date | Relevance |
                |-------|-----|-----------|------|-----------|
                | Spring Docs | https://spring.io | VMware | 2024 | official |
                """;
        ResearchOutput out = run(text);
        assertThat(out.sources()).hasSize(1);
        ResearchSource s = out.sources().get(0);
        assertThat(s.title()).isEqualTo("Spring Docs");
        assertThat(s.url()).isEqualTo("https://spring.io");
        assertThat(s.publisher()).isEqualTo("VMware");
        assertThat(s.date()).isEqualTo("2024");
        assertThat(s.relevance()).isEqualTo("official");
    }

    @Test
    void delimitedSourceRowParsed() {
        String text = """
                SOURCES:
                - Spring Reference \u2014 https://docs.spring.io/spring-framework \u2014 official reference
                """;
        ResearchOutput out = run(text);
        assertThat(out.sources()).hasSize(1);
        ResearchSource s = out.sources().get(0);
        assertThat(s.title()).isEqualTo("Spring Reference");
        assertThat(s.url()).isEqualTo("https://docs.spring.io/spring-framework");
        assertThat(s.relevance()).contains("official reference");
    }

    @Test
    void sourcePrefixLineParsed() {
        String text = """
                SOURCES:
                Source: https://spring.io/projects/spring-framework
                """;
        ResearchOutput out = run(text);
        assertThat(out.sources()).hasSize(1);
        assertThat(out.sources().get(0).url())
                .isEqualTo("https://spring.io/projects/spring-framework");
    }

    @Test
    void malformedSourceRowDoesNotAbortParsingOfOthers() {
        String text = """
                SOURCES:
                - title: Good Source
                  url: https://example.com
                - !!! garbage row with no structure !!!
                - title: Second Source
                  relevance: still parsed
                """;
        ResearchOutput out = run(text);
        assertThat(out.sources()).anySatisfy(s -> assertThat(s.title()).isEqualTo("Good Source"));
        assertThat(out.sources()).anySatisfy(s -> assertThat(s.title()).isEqualTo("Second Source"));
    }

    @Test
    void missingSourcesSectionStillReturnsAnswer() {
        String text = """
                SUMMARY:
                A summary.

                ANSWER:
                Detailed answer without sources.
                """;
        ResearchOutput out = run(text);
        assertThat(out.answer()).contains("without sources");
        assertThat(out.sources()).isEmpty();
    }

    @Test
    void duplicatedSectionHeadingsAreMerged() {
        String text = """
                ANSWER:
                Part one.

                ANSWER:
                Part two.
                """;
        ResearchOutput out = run(text);
        assertThat(out.answer()).contains("Part one.").contains("Part two.");
    }

    @Test
    void longResponseIsTruncated() {
        StringBuilder sb = new StringBuilder("ANSWER:\n");
        sb.append("x".repeat(500));
        ResearchOutput out = tool(sb.toString(), 80).handle(args());
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void markdownCodeBlockIsPreservedInAnswer() {
        String text = "ANSWER:\nUse this:\n```java\nvar x = 1;\n```\nDone.\n";
        ResearchOutput out = run(text);
        assertThat(out.answer()).contains("var x = 1;");
    }

    @Test
    void urlsInAnswerTextArePreserved() {
        String text = "ANSWER:\nSee https://spring.io/guides for details.\n";
        ResearchOutput out = run(text);
        assertThat(out.answer()).contains("https://spring.io/guides");
    }

    @Test
    void citationsEmbeddedInTextArePreserved() {
        String text = "ANSWER:\nSpring is a framework [1] with DI [2].\n";
        ResearchOutput out = run(text);
        assertThat(out.answer()).contains("[1]").contains("[2]");
    }

    @Test
    void nonCompliantTextFallsBackToAnswer() {
        ResearchOutput out = run("Just an unstructured paragraph with no headers at all.");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.answer()).contains("unstructured paragraph");
    }
}
