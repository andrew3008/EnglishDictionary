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
import space.br1440.platform.devtools.opusmcp.prompt.RefactorPlanPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.RefactorPlanTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code refactor_plan_with_opus} at the serialized-JSON boundary, using
 * a deterministic fake model client (no network). Verifies stable output keys, structured steps, no
 * API key leakage, and pre-call guard behavior.
 */
class RefactorPlanJsonPipelineTest {

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

    private RefactorPlanTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new RefactorPlanTool(
                config, client, new RefactorPlanPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String code, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Refactor for maintainability");
        m.put("language", "java");
        m.put("code", code);
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("refactorGoal", "maintainability");
        m.put("scope", "class");
        m.put("compatibilityMode", "preserve_behavior");
        m.put("riskLevel", "high");
        m.put("outputFormat", "migration_slices");
        return m;
    }

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredSteps() throws Exception {
        String structured = "SUMMARY:\nSplit god class.\n\nPLAN:\nExtract responsibilities.\n\n"
                + "STEPS:\n- id: RF-001\n  title: Extract service\n  category: architecture\n"
                + "  risk: HIGH\n  requiresBehaviorChange: false\n  description: move logic.\n"
                + "  verification: run tests.\n\n"
                + "AFFECTED_AREAS:\n- OrderService\n\nROLLBACK_PLAN:\nRevert commit.\n\n"
                + "RISKS:\n- regression risk\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args("class God {}", "no repo"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "plan", "steps", "affectedAreas",
                "risks", "safetyNotes", "assumptions", "testsToRun", "rollbackPlan", "truncated",
                "inputTokenEstimate", "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(node.path("rollbackPlan").asText()).contains("Revert commit");

        JsonNode steps = node.path("steps");
        assertThat(steps.isArray()).isTrue();
        assertThat(steps).hasSize(1);
        JsonNode s0 = steps.get(0);
        assertThat(s0.path("id").asText()).isEqualTo("RF-001");
        assertThat(s0.path("title").asText()).isEqualTo("Extract service");
        assertThat(s0.path("category").asText()).isEqualTo("architecture");
        assertThat(s0.path("risk").asText()).isEqualTo("HIGH");
        assertThat(s0.path("requiresBehaviorChange").asBoolean()).isFalse();
        assertThat(s0.has("description")).isTrue();
        assertThat(s0.has("verification")).isTrue();
        assertThat(node.path("affectedAreas").get(0).asText()).isEqualTo("OrderService");
        assertThat(node.path("risks").get(0).asText()).isEqualTo("regression risk");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("PLAN:\nfine\n");
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
