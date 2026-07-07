package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityConfig;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchClient;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchClientException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchWithPerplexityToolTest {

    static final class FakeResearchClient implements ResearchClient {
        final AtomicInteger calls = new AtomicInteger();
        String systemSeen;
        String userSeen;
        final String text;

        FakeResearchClient(String text) {
            this.text = text;
        }

        @Override
        public ResearchResponse research(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            this.systemSeen = systemPrompt;
            this.userSeen = userPrompt;
            return new ResearchResponse(text, 20, 30, "sonar-deep-research", "pplx-req-1");
        }
    }

    private ResearchWithPerplexityTool tool(ResearchClient client, PerplexityConfig config) {
        return new ResearchWithPerplexityTool(
                config, client, new ResearchPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 50_000), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private PerplexityConfig configuredWithKey() {
        return new PerplexityConfig(Map.of(PerplexityConfig.ENV_API_KEY, "secret-key-value"));
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Understand the Spring Framework basics");
        m.put("researchQuestion", "What is the Spring Framework?");
        m.put("context", "evaluating Java frameworks");
        m.put("constraints", "cite official docs");
        m.put("sourcePreference", "official_docs");
        m.put("freshness", "latest");
        m.put("depth", "standard");
        m.put("outputFormat", "report");
        m.put("riskLevel", "low");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            Spring is a Java application framework.

            ANSWER:
            Spring Framework is an application framework and IoC container for the JVM.

            KEY_FINDINGS:
            - Provides dependency injection
            - Modular ecosystem

            SOURCES:
            - title: Spring Documentation
              url: https://spring.io/projects/spring-framework
              publisher: Spring / VMware
              date: 2024
              relevance: official documentation
            - title: Baeldung Spring Guide
              url: https://www.baeldung.com/spring-intro
              publisher: Baeldung
              date: 2023
              relevance: tutorial overview

            RECOMMENDATIONS:
            - Prefer Spring Boot for new apps

            RISKS:
            - Version compatibility drift

            SAFETY_NOTES:
            - Public sources only

            ASSUMPTIONS:
            - JVM-based project

            FOLLOW_UP_QUESTIONS:
            - Which Spring version is targeted?
            """;

    @Test
    void okResponseParsesAllSections() {
        FakeResearchClient client = new FakeResearchClient(STRUCTURED);
        ResearchOutput out = tool(client, configuredWithKey()).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).isEqualTo("Spring is a Java application framework.");
        assertThat(out.answer()).contains("IoC container");
        assertThat(out.keyFindings()).containsExactly("Provides dependency injection", "Modular ecosystem");
        assertThat(out.sources()).hasSize(2);
        ResearchSource first = out.sources().get(0);
        assertThat(first.title()).isEqualTo("Spring Documentation");
        assertThat(first.url()).isEqualTo("https://spring.io/projects/spring-framework");
        assertThat(first.publisher()).isEqualTo("Spring / VMware");
        assertThat(first.date()).isEqualTo("2024");
        assertThat(first.relevance()).isEqualTo("official documentation");
        assertThat(out.recommendations()).containsExactly("Prefer Spring Boot for new apps");
        assertThat(out.risks()).containsExactly("Version compatibility drift");
        assertThat(out.safetyNotes()).containsExactly("Public sources only");
        assertThat(out.assumptions()).containsExactly("JVM-based project");
        assertThat(out.followUpQuestions()).containsExactly("Which Spring version is targeted?");
        assertThat(out.model()).isEqualTo("sonar-deep-research");
        assertThat(out.requestId()).isEqualTo("pplx-req-1");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void researchQuestionIsForwardedToProviderPrompt() {
        FakeResearchClient client = new FakeResearchClient(STRUCTURED);
        tool(client, configuredWithKey()).handle(args());

        assertThat(client.userSeen).contains("What is the Spring Framework?");
        assertThat(client.systemSeen).contains("public web-grounded technical research");
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        FakeResearchClient client = new FakeResearchClient("unused");
        Map<String, Object> bad = args();
        bad.remove("researchQuestion");
        ResearchOutput out = tool(client, configuredWithKey()).handle(bad);

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        FakeResearchClient client = new FakeResearchClient("unused");
        Map<String, Object> bad = args();
        bad.put("freshness", "yesterday");
        ResearchOutput out = tool(client, configuredWithKey()).handle(bad);

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void missingApiKeyReturnsModelErrorWithoutProviderCall() {
        FakeResearchClient client = new FakeResearchClient("unused");
        PerplexityConfig noKey = new PerplexityConfig(Map.of());
        ResearchOutput out = tool(client, noKey).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.summary()).contains("PERPLEXITY_API_KEY is not set");
        assertThat(out.safetyNotes()).containsExactly("No provider call was made.");
        assertThat(out.followUpQuestions())
                .anySatisfy(q -> assertThat(q).contains("PERPLEXITY_API_KEY"));
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void nonCompliantResponseStillReturnsTextInAnswer() {
        FakeResearchClient client = new FakeResearchClient("Just a plain answer with no sections.");
        ResearchOutput out = tool(client, configuredWithKey()).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.answer()).contains("Just a plain answer");
    }

    @Test
    void malformedSourceDoesNotAbortParsingOfOthers() {
        String text = """
                ANSWER:
                Some answer.

                SOURCES:
                - title: Good Source
                  url: https://example.com
                - this is a bare line with no key
                - title: Second Source
                  relevance: still parsed
                """;
        FakeResearchClient client = new FakeResearchClient(text);
        ResearchOutput out = tool(client, configuredWithKey()).handle(args());

        assertThat(out.sources()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(out.sources()).anySatisfy(s -> assertThat(s.title()).isEqualTo("Good Source"));
        assertThat(out.sources()).anySatisfy(s -> assertThat(s.title()).isEqualTo("Second Source"));
    }

    @Test
    void providerAuthErrorMapsToModelError() {
        ResearchClient client = (s, u) -> {
            throw new ResearchClientException(
                    space.br1440.platform.devtools.opusmcp.perplexity.PerplexityDiagnosticCategory.AUTH_ERROR,
                    401, "unauthorized");
        };
        ResearchOutput out = tool(client, configuredWithKey()).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.summary()).doesNotContain("unauthorized");
    }

    @Test
    void providerRateLimitMapsToBudgetExceeded() {
        ResearchClient client = (s, u) -> {
            throw new ResearchClientException(
                    space.br1440.platform.devtools.opusmcp.perplexity.PerplexityDiagnosticCategory.RATE_LIMIT_OR_QUOTA,
                    429, "rate limited");
        };
        ResearchOutput out = tool(client, configuredWithKey()).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }
}
