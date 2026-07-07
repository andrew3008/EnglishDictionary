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
import space.br1440.platform.devtools.opusmcp.prompt.ExplainDiffPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.ExplainDiffTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code explain_diff_with_opus} at the serialized-JSON boundary, using a
 * deterministic fake model client (no network). Verifies stable output keys, structured findings, no
 * API key leakage, and pre-call guard behavior.
 */
class ExplainDiffJsonPipelineTest {

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

    private ExplainDiffTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new ExplainDiffTool(
                config, client, new ExplainDiffPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String diff, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Explain and pre-merge review this diff");
        m.put("language", "java");
        m.put("diff", diff);
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("diffFormat", "git_diff");
        m.put("analysisFocus", "all");
        m.put("riskLevel", "high");
        m.put("outputFormat", "merge_review");
        return m;
    }

    private static final String SAFE_DIFF =
            "--- a/Calc.java\n+++ b/Calc.java\n@@ -1 +1 @@\n-return a+b;\n+return a-b;";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredFindings() throws Exception {
        String structured = "SUMMARY:\nChanges add to subtract.\n\nEXPLANATION:\nOperator flipped.\n\n"
                + "CHANGED_FILES:\n- Calc.java\n\nBEHAVIOR_CHANGES:\n- Result is now a-b\n\n"
                + "FINDINGS:\n- severity: BLOCKER\n  category: correctness\n  title: Wrong operator\n"
                + "  details: subtraction not addition.\n  recommendation: revert.\n\n"
                + "RISKS:\n- incorrect results\n\nMERGE_RECOMMENDATION:\nREQUEST_CHANGES\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE_DIFF, "no repo"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "explanation", "changedFiles",
                "behaviorChanges", "risks", "findings", "testsToRun", "safetyNotes", "assumptions",
                "mergeRecommendation", "truncated", "inputTokenEstimate", "outputTokenEstimate",
                "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(node.path("mergeRecommendation").asText()).isEqualTo("REQUEST_CHANGES");
        assertThat(node.path("changedFiles").get(0).asText()).isEqualTo("Calc.java");
        assertThat(node.path("behaviorChanges").get(0).asText()).isEqualTo("Result is now a-b");

        JsonNode findings = node.path("findings");
        assertThat(findings.isArray()).isTrue();
        assertThat(findings).hasSize(1);
        JsonNode f0 = findings.get(0);
        assertThat(f0.path("severity").asText()).isEqualTo("BLOCKER");
        assertThat(f0.path("category").asText()).isEqualTo("correctness");
        assertThat(f0.path("title").asText()).isEqualTo("Wrong operator");
        assertThat(f0.has("details")).isTrue();
        assertThat(f0.has("recommendation")).isTrue();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("EXPLANATION:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE_DIFF, "no repo"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInDiffBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("+api_key=ABC123SECRETVALUE", "no repo"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInContextBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args(SAFE_DIFF, "please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }
}
