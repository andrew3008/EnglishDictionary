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
import space.br1440.platform.devtools.opusmcp.prompt.GenerateTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code generate_tests_with_opus} at the serialized-JSON boundary, using
 * a deterministic fake model client (no network). Verifies stable output keys, structured test cases,
 * no API key leakage, and pre-call guard behavior.
 */
class GenerateTestsJsonPipelineTest {

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

    private GenerateTestsTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new GenerateTestsTool(
                config, client, new GenerateTestsPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String code, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate unit tests");
        m.put("language", "java");
        m.put("code", code);
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("testFramework", "junit5");
        m.put("testType", "unit");
        m.put("coverageFocus", "edge_cases");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_tests");
        return m;
    }

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredTestCases() throws Exception {
        String structured = "SUMMARY:\nTests for add.\n\nTEST_PLAN:\nCover overflow.\n\n"
                + "TEST_CODE:\n```java\n@Test void t(){}\n```\n\n"
                + "TEST_CASES:\n- name: overflow\n  type: property\n  priority: HIGH\n"
                + "  purpose: detect overflow\n  given: max+1\n  when: add\n  then: overflow\n\n"
                + "RISKS:\n- overflow undefined\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args("public static int add(int a,int b){return a+b;}", "no repo"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "testPlan", "testCode", "testCases",
                "risks", "safetyNotes", "assumptions", "testsToRun", "truncated", "inputTokenEstimate",
                "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(node.path("testCode").asText()).contains("@Test void t(){}");

        JsonNode testCases = node.path("testCases");
        assertThat(testCases.isArray()).isTrue();
        assertThat(testCases).hasSize(1);
        JsonNode tc0 = testCases.get(0);
        assertThat(tc0.path("name").asText()).isEqualTo("overflow");
        assertThat(tc0.path("type").asText()).isEqualTo("property");
        assertThat(tc0.path("priority").asText()).isEqualTo("HIGH");
        assertThat(tc0.has("purpose")).isTrue();
        assertThat(tc0.has("given")).isTrue();
        assertThat(tc0.has("when")).isTrue();
        assertThat(tc0.has("then")).isTrue();
        assertThat(node.path("risks").get(0).asText()).isEqualTo("overflow undefined");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("TEST_PLAN:\nfine\n");
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
