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
import space.br1440.platform.devtools.opusmcp.prompt.PromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.GenerateCodeTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test at the serialized-JSON boundary ({@code handleAsJson}) using a
 * deterministic fake model client (no Mockito, no network). Verifies the output schema keys and that
 * the {@code result} field carries only the RESULT body.
 */
class GenerateCodeJsonPipelineTest {

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
            return new OpusResponse(text, 11, 7);
        }
    }

    private GenerateCodeTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new GenerateCodeTool(
                config, client, new PromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate a Java method that adds two integers");
        m.put("language", "java");
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("outputFormat", "code_block");
        m.put("riskLevel", "low");
        return m;
    }

    @Test
    void serializedOutputHasStableSchemaKeysAndResultBodyOnly() throws Exception {
        String structured = "SUMMARY:\nAdds two integers.\n\n"
                + "RESULT:\n```java\npublic static int add(int a, int b) {\n    return a + b;\n}\n```\n\n"
                + "ASSUMPTIONS:\n- assumes Java 21\n\nRISKS:\n- overflow\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args("no repository context"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "result", "risks", "safetyNotes",
                "assumptions", "testsToRun", "truncated", "inputTokenEstimate",
                "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(node.path("result").asText())
                .isEqualTo("```java\npublic static int add(int a, int b) {\n    return a + b;\n}\n```");
        assertThat(node.path("result").asText())
                .doesNotContain("SUMMARY:").doesNotContain("ASSUMPTIONS:").doesNotContain("RISKS:");
        assertThat(node.path("assumptions").get(0).asText()).isEqualTo("assumes Java 21");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() throws Exception {
        FakeOpusClient client = new FakeOpusClient("RESULT:\n```java\nint x=1;\n```");
        String json = tool(client).handleAsJson(args("no repository context"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInputBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("api_key=ABC123SECRETVALUE"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInputBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }
}
