package space.br1440.platform.devtools.opusmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import space.br1440.platform.devtools.opusmcp.tool.ResearchWithPerplexityTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code research_with_perplexity} at the serialized-JSON boundary, using
 * a deterministic fake research client (no network). Verifies stable output keys, structured sources,
 * no API key leakage, pre-call guard behavior, and safe missing-key behavior.
 */
class ResearchWithPerplexityJsonPipelineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class FakeResearchClient implements ResearchClient {
        final AtomicInteger calls = new AtomicInteger();
        final String text;

        FakeResearchClient(String text) {
            this.text = text;
        }

        @Override
        public ResearchResponse research(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            return new ResearchResponse(text, 12, 18, "sonar-deep-research", "pplx-1");
        }
    }

    private ResearchWithPerplexityTool tool(ResearchClient client, PerplexityConfig config) {
        return new ResearchWithPerplexityTool(
                config, client, new ResearchPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 50_000), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private PerplexityConfig keyConfig() {
        return new PerplexityConfig(Map.of(PerplexityConfig.ENV_API_KEY, "secret-key-value"));
    }

    private Map<String, Object> args(String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "research task");
        m.put("researchQuestion", "What is Spring Framework?");
        m.put("context", context);
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
            Spring is a Java framework.

            ANSWER:
            Spring is an application framework for the JVM.

            KEY_FINDINGS:
            - DI container

            SOURCES:
            - title: Spring Docs
              url: https://spring.io
              publisher: VMware
              date: 2024
              relevance: official
            """;

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredSources() throws Exception {
        FakeResearchClient client = new FakeResearchClient(STRUCTURED);
        String json = tool(client, keyConfig()).handleAsJson(args("no repo"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "answer", "keyFindings", "sources",
                "recommendations", "risks", "safetyNotes", "assumptions", "followUpQuestions",
                "truncated", "inputTokenEstimate", "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("sonar-deep-research");

        JsonNode sources = node.path("sources");
        assertThat(sources.isArray()).isTrue();
        assertThat(sources).hasSize(1);
        JsonNode s0 = sources.get(0);
        assertThat(s0.path("title").asText()).isEqualTo("Spring Docs");
        assertThat(s0.path("url").asText()).isEqualTo("https://spring.io");
        assertThat(s0.path("publisher").asText()).isEqualTo("VMware");
        assertThat(s0.has("date")).isTrue();
        assertThat(s0.has("relevance")).isTrue();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeResearchClient client = new FakeResearchClient("ANSWER:\nfine\n");
        String json = tool(client, keyConfig()).handleAsJson(args("no repo"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInContextBlocksBeforeProviderCallAtJsonBoundary() throws Exception {
        FakeResearchClient client = new FakeResearchClient("unused");
        String json = tool(client, keyConfig()).handleAsJson(args("api_key=ABC123SECRETVALUE"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void envReferenceBlocksBeforeProviderCallAtJsonBoundary() throws Exception {
        FakeResearchClient client = new FakeResearchClient("unused");
        String json = tool(client, keyConfig()).handleAsJson(args("read .env please"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void missingKeyReturnsModelErrorAtJsonBoundaryWithoutProviderCall() throws Exception {
        FakeResearchClient client = new FakeResearchClient("unused");
        String json = tool(client, new PerplexityConfig(Map.of())).handleAsJson(args("no repo"));
        JsonNode node = mapper.readTree(json);
        assertThat(node.path("status").asText()).isEqualTo("MODEL_ERROR");
        assertThat(node.path("summary").asText()).contains("PERPLEXITY_API_KEY is not set");
        assertThat(node.path("model").asText()).isEqualTo("sonar-deep-research");
        assertThat(client.calls.get()).isZero();
    }
}
