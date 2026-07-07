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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8D golden mocked-response pack: deterministic Perplexity-like outputs fed through a fake
 * {@link ResearchClient} (no network, no API key) for representative research-mode combinations and
 * response shapes. These pin the offline parsing/serialization contract for architecture review.
 */
class ResearchGoldenResponseTest {

    private static final class FakeResearchClient implements ResearchClient {
        private final String text;

        FakeResearchClient(String text) {
            this.text = text;
        }

        @Override
        public ResearchResponse research(String systemPrompt, String userPrompt) {
            return new ResearchResponse(text, 25, 60, "sonar-deep-research", "pplx-golden");
        }
    }

    private ResearchWithPerplexityTool tool(String responseText, int maxOutputChars) {
        return new ResearchWithPerplexityTool(
                new PerplexityConfig(Map.of(PerplexityConfig.ENV_API_KEY, "k")),
                new FakeResearchClient(responseText), new ResearchPromptBuilder(),
                new SecretScanner(), new DenyList(),
                new LimitsGuard(20_000, 5_000, maxOutputChars),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String sourcePreference, String freshness, String depth,
            String outputFormat) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Public research");
        m.put("researchQuestion", "What is the recommended option and why?");
        m.put("context", "");
        m.put("constraints", "");
        m.put("sourcePreference", sourcePreference);
        m.put("freshness", freshness);
        m.put("depth", depth);
        m.put("outputFormat", outputFormat);
        m.put("riskLevel", "low");
        return m;
    }

    private ResearchOutput run(String text, String pref, String fresh, String depth, String fmt) {
        return tool(text, 50_000).handle(args(pref, fresh, depth, fmt));
    }

    @Test
    void officialDocsLatestBrief() {
        String text = """
                SUMMARY:
                SLF4J is the de-facto Java logging facade.

                ANSWER:
                Use SLF4J as the facade with a backend like Logback.

                KEY_FINDINGS:
                - SLF4J is a facade, not an implementation

                SOURCES:
                - title: SLF4J Manual
                  url: https://www.slf4j.org/manual.html
                  publisher: QOS.ch
                  date: 2024
                  relevance: official manual
                """;
        ResearchOutput out = run(text, "official_docs", "latest", "quick", "brief");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.answer()).contains("SLF4J");
        assertThat(out.sources()).hasSize(1);
        assertThat(out.sources().get(0).url()).isEqualTo("https://www.slf4j.org/manual.html");
    }

    @Test
    void officialDocsStableReport() {
        String text = """
                SUMMARY:
                Spring Framework overview.

                ANSWER:
                Spring provides IoC, DI and a broad module ecosystem.

                KEY_FINDINGS:
                - Dependency injection
                - Modular

                SOURCES:
                - title: Spring Framework Reference
                  url: https://docs.spring.io/spring-framework/reference/
                  publisher: VMware
                  date: 2024
                  relevance: official reference

                RECOMMENDATIONS:
                - Use Spring Boot for new apps

                RISKS:
                - Upgrade churn across major versions
                """;
        ResearchOutput out = run(text, "official_docs", "stable", "standard", "report");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.keyFindings()).contains("Dependency injection", "Modular");
        assertThat(out.recommendations()).contains("Use Spring Boot for new apps");
        assertThat(out.risks()).isNotEmpty();
    }

    @Test
    void industryBestPracticesLast12MonthsDecisionMemo() {
        String text = """
                SUMMARY:
                Recommendation: adopt structured logging.

                ANSWER:
                Decision: adopt JSON structured logging for services.

                KEY_FINDINGS:
                - Structured logs improve searchability

                RECOMMENDATIONS:
                - Emit JSON logs in production

                RISKS:
                - Higher log volume cost

                ASSUMPTIONS:
                - Centralized log aggregation exists

                SOURCES:
                - title: 12-Factor Logs
                  url: https://12factor.net/logs
                  relevance: best practice
                """;
        ResearchOutput out = run(text, "industry_best_practices", "last_12_months", "standard",
                "decision_memo");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.recommendations()).isNotEmpty();
        assertThat(out.assumptions()).isNotEmpty();
        assertThat(out.sources()).hasSize(1);
    }

    @Test
    void mixedDeepSourceTableWithMarkdownTable() {
        String text = """
                ANSWER:
                Comparison of options is below.

                SOURCES:
                | Title | URL | Publisher | Date | Relevance |
                |-------|-----|-----------|------|-----------|
                | Spring Docs | https://spring.io | VMware | 2024 | official |
                | Baeldung | https://www.baeldung.com | Baeldung | 2023 | tutorial |
                """;
        ResearchOutput out = run(text, "mixed", "stable", "deep", "source_table");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.sources()).hasSize(2);
        assertThat(out.sources().get(0).publisher()).isEqualTo("VMware");
        assertThat(out.sources().get(1).url()).isEqualTo("https://www.baeldung.com");
    }

    @Test
    void academicStableReport() {
        String text = """
                SUMMARY:
                Consensus algorithms overview.

                ANSWER:
                Raft is an understandable consensus algorithm [1].

                KEY_FINDINGS:
                - Raft separates leader election from log replication

                SOURCES:
                - title: In Search of an Understandable Consensus Algorithm
                  url: https://raft.github.io/raft.pdf
                  publisher: Stanford
                  date: 2014
                  relevance: original paper
                """;
        ResearchOutput out = run(text, "academic", "stable", "standard", "report");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.answer()).contains("[1]");
        assertThat(out.sources().get(0).publisher()).isEqualTo("Stanford");
    }

    @Test
    void responseWithInlineCitationsPreservesMarkers() {
        String text = """
                ANSWER:
                The framework supports DI [1] and AOP [2]. See sources for details.

                SOURCES:
                - title: Docs
                  url: https://example.com
                """;
        ResearchOutput out = run(text, "mixed", "latest", "standard", "report");
        assertThat(out.answer()).contains("[1]").contains("[2]");
    }

    @Test
    void responseWithDuplicateHeadingsIsMerged() {
        String text = """
                ANSWER:
                First part.

                ANSWER:
                Second part.
                """;
        ResearchOutput out = run(text, "mixed", "stable", "standard", "report");
        assertThat(out.answer()).contains("First part.").contains("Second part.");
    }

    @Test
    void responseWithNoSourcesStillOk() {
        String text = "ANSWER:\nA grounded answer with no explicit sources section.\n";
        ResearchOutput out = run(text, "mixed", "stable", "quick", "brief");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.sources()).isEmpty();
        assertThat(out.answer()).contains("no explicit sources");
    }

    @Test
    void responseWithMalformedSourceRowsDoesNotFail() {
        String text = """
                ANSWER:
                Answer text.

                SOURCES:
                - title: Valid Source
                  url: https://valid.example
                - %%% broken row %%%
                - title: Another Valid
                  url: https://valid2.example
                """;
        ResearchOutput out = run(text, "official_docs", "latest", "standard", "report");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.sources()).anySatisfy(s -> assertThat(s.title()).isEqualTo("Valid Source"));
        assertThat(out.sources()).anySatisfy(s -> assertThat(s.title()).isEqualTo("Another Valid"));
    }

    @Test
    void longResponseIsTruncated() {
        String text = "ANSWER:\n" + "y".repeat(800);
        ResearchOutput out = tool(text, 100).handle(args("mixed", "stable", "deep", "report"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.truncated()).isTrue();
    }
}
