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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.ReviewCodeTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code review_code_with_opus} at the serialized-JSON boundary, using a
 * deterministic fake model client (no network). Verifies stable output keys, structured findings, no
 * API key leakage, and pre-call guard behavior.
 */
class ReviewCodeJsonPipelineTest {

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
            return new OpusResponse(text, 12, 6);
        }
    }

    private ReviewCodeTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new ReviewCodeTool(
                config, client, new ReviewPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String code, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review for security issues");
        m.put("language", "java");
        m.put("code", code);
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("reviewFocus", "security");
        m.put("riskLevel", "high");
        m.put("outputFormat", "structured_review");
        return m;
    }

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredFindings() throws Exception {
        String structured = "SUMMARY:\nLooks risky.\n\nREVIEW:\nUses string concatenation in SQL.\n\n"
                + "FINDINGS:\n- severity: BLOCKER\n  category: security\n  title: SQL injection\n"
                + "  details: query built via concatenation.\n  recommendation: use parameters.\n\n"
                + "RISKS:\n- data exfiltration\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args("String q=\"select * from t where x=\"+v;", "no repo"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "review", "findings", "risks",
                "safetyNotes", "assumptions", "testsToRun", "truncated", "inputTokenEstimate",
                "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");

        JsonNode findings = node.path("findings");
        assertThat(findings.isArray()).isTrue();
        assertThat(findings).hasSize(1);
        JsonNode f0 = findings.get(0);
        assertThat(f0.path("severity").asText()).isEqualTo("BLOCKER");
        assertThat(f0.path("category").asText()).isEqualTo("security");
        assertThat(f0.path("title").asText()).isEqualTo("SQL injection");
        assertThat(f0.has("details")).isTrue();
        assertThat(f0.has("recommendation")).isTrue();
        assertThat(node.path("risks").get(0).asText()).isEqualTo("data exfiltration");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nfine\n");
        String json = tool(client).handleAsJson(args("int x=1;", "no repo"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInCodeBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("api_key=ABC123SECRETVALUE", "no repo"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInContextBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("int x=1;", "please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }
}
