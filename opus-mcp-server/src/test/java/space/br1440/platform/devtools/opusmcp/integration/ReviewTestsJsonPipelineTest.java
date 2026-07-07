package space.br1440.platform.devtools.opusmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.ModelRegistry;
import space.br1440.platform.devtools.opusmcp.model.OpusClient;
import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.model.OpusRequest;
import space.br1440.platform.devtools.opusmcp.model.OpusResponse;
import space.br1440.platform.devtools.opusmcp.prompt.ReviewTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.ReviewTestsTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code review_tests_with_opus} at the serialized-JSON boundary, using a
 * deterministic fake model client (no network). Verifies stable output keys, structured findings
 * fidelity, no API key leakage, and pre-call guard behavior.
 */
class ReviewTestsJsonPipelineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class FakeOpusClient implements OpusClient {
        final AtomicInteger calls = new AtomicInteger();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            calls.incrementAndGet();
            return new OpusResponse(text, 14, 8);
        }
    }

    private ReviewTestsTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new ReviewTestsTool(
                config, client, new ReviewTestsPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String testCode, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review tests");
        m.put("language", "java");
        m.put("testCode", testCode);
        m.put("testIntent", "Verify behavior");
        m.put("constraints", constraints);
        m.put("testFramework", "junit5");
        m.put("testType", "unit");
        m.put("reviewFocus", "all");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String SAFE = "@Test void t() { assertEquals(1, svc.f()); }";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredFindings() throws Exception {
        String structured = "SUMMARY:\nWeak test.\n\nVERDICT:\nREQUEST_CHANGES\n\n"
                + "REVIEW:\nThe test does not verify behavior.\n\n"
                + "FINDINGS:\n- severity: HIGH\n  category: assertions\n  title: Tautology\n"
                + "  details: always passes\n  recommendation: assert real behavior\n\n"
                + "COVERAGE_GAPS:\n- duplicate path untested\n\n"
                + "SUGGESTED_TEST_CASES:\n- addRejectsDuplicate\n\n"
                + "CI_READINESS_CHECKS:\n- fails when behavior breaks\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "verdict", "review", "findings",
                "coverageGaps", "assertionIssues", "flakinessRisks", "mockingIssues", "testDataIssues",
                "integrationBoundaryIssues", "maintainabilityIssues", "suggestedTestCases",
                "ciReadinessChecks", "openQuestions", "risks", "safetyNotes", "assumptions",
                "truncated", "inputTokenEstimate", "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("verdict").asText()).isEqualTo("REQUEST_CHANGES");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");

        JsonNode findings = node.path("findings");
        assertThat(findings.isArray()).isTrue();
        assertThat(findings).hasSize(1);
        JsonNode f = findings.get(0);
        assertThat(f.path("severity").asText()).isEqualTo("HIGH");
        assertThat(f.path("category").asText()).isEqualTo("assertions");
        assertThat(f.path("title").asText()).isEqualTo("Tautology");
        assertThat(node.path("coverageGaps")).hasSize(1);
        assertThat(node.path("suggestedTestCases")).hasSize(1);
        assertThat(node.path("ciReadinessChecks")).hasSize(1);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInTestCodeBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("api_key=ABC123SECRETVALUE", "c"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInConstraintsBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args(SAFE, "please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }
}
