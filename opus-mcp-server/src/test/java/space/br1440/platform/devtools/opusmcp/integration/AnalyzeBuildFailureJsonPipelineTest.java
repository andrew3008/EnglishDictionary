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
import space.br1440.platform.devtools.opusmcp.prompt.AnalyzeBuildFailurePromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.AnalyzeBuildFailureTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code analyze_build_failure_with_opus} at the serialized-JSON
 * boundary, using a deterministic fake model client (no network). Verifies stable output keys,
 * structured fix options, no API key leakage, and pre-call guard behavior.
 */
class AnalyzeBuildFailureJsonPipelineTest {

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

    private AnalyzeBuildFailureTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new AnalyzeBuildFailureTool(
                config, client, new AnalyzeBuildFailurePromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String failureLog, String buildContext) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Diagnose and propose minimal fix");
        m.put("failureLog", failureLog);
        m.put("relevantCode", "class Calc { int add(int a,int b){return addExact(a,b);} }");
        m.put("buildContext", buildContext);
        m.put("constraints", "Java 21");
        m.put("failureType", "compile");
        m.put("language", "java");
        m.put("riskLevel", "high");
        m.put("outputFormat", "fix_plan");
        return m;
    }

    private static final String SAFE_LOG = "Calc.java:1: error: cannot find symbol\n  symbol: method addExact";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredFixOptions() throws Exception {
        String structured = "SUMMARY:\nMissing Math qualifier.\n\n"
                + "ROOT_CAUSE_HYPOTHESES:\n- addExact not imported\n\n"
                + "MOST_LIKELY_CAUSE:\nMath.addExact referenced unqualified.\n\n"
                + "EVIDENCE:\n- cannot find symbol: method addExact\n\n"
                + "FIX_OPTIONS:\n- title: Qualify with Math\n  description: Use Math.addExact.\n"
                + "  risk: LOW\n  requiresCodeChange: true\n  requiresDependencyChange: false\n\n"
                + "MINIMAL_PATCH_SUGGESTION:\n```java\n+return Math.addExact(a,b);\n```\n\n"
                + "TESTS_TO_RERUN:\n- ./gradlew compileJava\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE_LOG, "Gradle 8.7"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "rootCauseHypotheses", "mostLikelyCause",
                "evidence", "fixOptions", "minimalPatchSuggestion", "testsToRerun", "risks",
                "safetyNotes", "assumptions", "truncated", "inputTokenEstimate", "outputTokenEstimate",
                "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(node.path("rootCauseHypotheses").get(0).asText()).isEqualTo("addExact not imported");
        assertThat(node.path("minimalPatchSuggestion").asText()).contains("Math.addExact(a,b)");

        JsonNode fixOptions = node.path("fixOptions");
        assertThat(fixOptions.isArray()).isTrue();
        assertThat(fixOptions).hasSize(1);
        JsonNode f0 = fixOptions.get(0);
        assertThat(f0.path("title").asText()).isEqualTo("Qualify with Math");
        assertThat(f0.path("risk").asText()).isEqualTo("LOW");
        assertThat(f0.path("requiresCodeChange").asBoolean()).isTrue();
        assertThat(f0.path("requiresDependencyChange").asBoolean()).isFalse();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("MOST_LIKELY_CAUSE:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE_LOG, "Gradle 8.7"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInFailureLogBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("api_key=ABC123SECRETVALUE", "Gradle 8.7"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInBuildContextBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args(SAFE_LOG, "please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }
}
