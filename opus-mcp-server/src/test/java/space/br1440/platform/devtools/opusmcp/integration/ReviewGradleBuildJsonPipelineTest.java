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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewGradleBuildPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.ReviewGradleBuildTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code review_gradle_build_with_opus} at the serialized-JSON boundary,
 * using a deterministic fake model client (no network). Verifies stable output keys, structured
 * findings fidelity, no API key leakage, and pre-call guard behavior.
 */
class ReviewGradleBuildJsonPipelineTest {

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

    private ReviewGradleBuildTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new ReviewGradleBuildTool(
                config, client, new ReviewGradleBuildPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String buildFilesContext, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review build");
        m.put("buildFilesContext", buildFilesContext);
        m.put("constraints", constraints);
        m.put("projectType", "java_library");
        m.put("gradleDsl", "groovy");
        m.put("reviewFocus", "all");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String SAFE = "plugins { id 'java-library' }";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredFindings() throws Exception {
        String structured = "SUMMARY:\nWeak build.\n\nVERDICT:\nREQUEST_CHANGES\n\n"
                + "REVIEW:\nThe build hardcodes versions.\n\n"
                + "FINDINGS:\n- severity: HIGH\n  category: dependency_management\n  title: Hardcoded\n"
                + "  details: version not in catalog\n  recommendation: use the version catalog\n\n"
                + "CONFIGURATION_CACHE_ISSUES:\n- cache not enabled\n\n"
                + "DEPENDENCY_ISSUES:\n- version duplicated\n\n"
                + "RECOMMENDED_CHECKS:\n- run with --configuration-cache\n\n"
                + "SUGGESTED_CHANGES:\n- move to libs.versions.toml\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "verdict", "review", "findings",
                "configurationCacheIssues", "dependencyIssues", "pluginIssues", "taskGraphIssues",
                "multiModuleIssues", "testSetupIssues", "publishingIssues", "performanceIssues",
                "securityIssues", "compatibilityRisks", "recommendedChecks", "suggestedChanges",
                "openQuestions", "risks", "safetyNotes", "assumptions", "truncated",
                "inputTokenEstimate", "outputTokenEstimate", "model", "requestId"}) {
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
        assertThat(f.path("category").asText()).isEqualTo("dependency_management");
        assertThat(f.path("title").asText()).isEqualTo("Hardcoded");
        assertThat(node.path("configurationCacheIssues")).hasSize(1);
        assertThat(node.path("recommendedChecks")).hasSize(1);
        assertThat(node.path("suggestedChanges")).hasSize(1);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInBuildFilesBlocksBeforeModelCallAtJsonBoundary() throws Exception {
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
