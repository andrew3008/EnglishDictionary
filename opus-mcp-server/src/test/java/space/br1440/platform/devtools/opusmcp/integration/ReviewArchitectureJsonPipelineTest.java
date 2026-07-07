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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewArchitecturePromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.ReviewArchitectureTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end pipeline test for {@code review_architecture_with_opus} at the serialized-JSON boundary,
 * using a deterministic fake model client (no network). Verifies stable output keys, structured
 * findings/riskMatrix, no API key leakage, and pre-call guard behavior.
 */
class ReviewArchitectureJsonPipelineTest {

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

    private ReviewArchitectureTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new ReviewArchitectureTool(
                config, client, new ReviewArchitecturePromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String proposal, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review the starter split proposal");
        m.put("architectureProposal", proposal);
        m.put("context", "Spring Boot 3.x starter");
        m.put("constraints", constraints);
        m.put("reviewFocus", "api_compatibility");
        m.put("architectureScope", "multi_module");
        m.put("architectureStyle", "spring_boot_starter");
        m.put("compatibilityMode", "preserve_api");
        m.put("riskLevel", "high");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String SAFE_PROPOSAL = "Split the starter into core and autoconfigure modules.";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredFindingsAndRiskMatrix() throws Exception {
        String structured = "SUMMARY:\nReasonable split.\n\n"
                + "VERDICT:\nAPPROVE_WITH_CHANGES\n\n"
                + "REVIEW:\nFollows Spring Boot conventions.\n\n"
                + "FINDINGS:\n- severity: HIGH\n  category: api_compatibility\n"
                + "  title: Bean rename risk\n  details: moving beans may rename classes\n"
                + "  recommendation: keep names stable\n\n"
                + "RISK_MATRIX:\n- risk: ordering breakage\n  likelihood: MEDIUM\n  impact: HIGH\n"
                + "  mitigation: add ordering annotations\n\n"
                + "TESTS_TO_ADD:\n- AutoConfig contract test\n\n"
                + "OBSERVABILITY_CHECKS:\n- verify spans exported\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE_PROPOSAL, "Java 21"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "verdict", "review", "findings",
                "riskMatrix", "tradeOffs", "alternatives", "openQuestions", "testsToAdd",
                "observabilityChecks", "rolloutNotes", "rollbackNotes", "risks", "safetyNotes",
                "assumptions", "truncated", "inputTokenEstimate", "outputTokenEstimate", "model",
                "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(node.path("verdict").asText()).isEqualTo("APPROVE_WITH_CHANGES");

        JsonNode findings = node.path("findings");
        assertThat(findings.isArray()).isTrue();
        assertThat(findings).hasSize(1);
        JsonNode f0 = findings.get(0);
        assertThat(f0.path("severity").asText()).isEqualTo("HIGH");
        assertThat(f0.path("category").asText()).isEqualTo("api_compatibility");
        assertThat(f0.path("title").asText()).isEqualTo("Bean rename risk");

        JsonNode riskMatrix = node.path("riskMatrix");
        assertThat(riskMatrix.isArray()).isTrue();
        assertThat(riskMatrix).hasSize(1);
        JsonNode r0 = riskMatrix.get(0);
        assertThat(r0.path("risk").asText()).isEqualTo("ordering breakage");
        assertThat(r0.path("likelihood").asText()).isEqualTo("MEDIUM");
        assertThat(r0.path("impact").asText()).isEqualTo("HIGH");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE_PROPOSAL, "Java 21"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInProposalBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("api_key=ABC123SECRETVALUE", "c"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInConstraintsBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args(SAFE_PROPOSAL, "please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void providerTimeoutAtJsonBoundaryHasEmptyVerdictAndNoSecretLeak() throws Exception {
        OpusClient client = org.mockito.Mockito.mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.TIMEOUT, "timeout"));
        String json = tool(client).handleAsJson(args(SAFE_PROPOSAL, "Java 21"));
        JsonNode node = mapper.readTree(json);
        assertThat(node.path("status").asText()).isEqualTo("MODEL_ERROR");
        assertThat(node.path("summary").asText()).containsIgnoringCase("timed out");
        assertThat(node.path("verdict").asText()).isEmpty();
        assertThat(node.path("review").asText()).isEmpty();
        assertThat(node.path("findings").size()).isZero();
        assertThat(node.path("safetyNotes").size()).isGreaterThan(0);
        assertThat(json).doesNotContain("secret-key-value");
        assertThat(json).doesNotContain(SAFE_PROPOSAL);
    }
}
