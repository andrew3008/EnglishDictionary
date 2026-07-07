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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewMdxDocPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.ReviewMdxDocTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code review_mdx_doc_with_opus} at the serialized-JSON boundary,
 * using a deterministic fake model client (no network). Verifies stable output keys, structured
 * findings fidelity, no API key leakage, and pre-call guard behavior.
 */
class ReviewMdxDocJsonPipelineTest {

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

    private ReviewMdxDocTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new ReviewMdxDocTool(
                config, client, new ReviewMdxDocPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String mdxContent, String libraryContext, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review a how-to for enabling tracing");
        m.put("mdxContent", mdxContent);
        m.put("docSubject", "Tracing Starter");
        m.put("targetAudience", "application_developers");
        m.put("libraryContext", libraryContext);
        m.put("constraints", constraints);
        m.put("reviewFocus", "all");
        m.put("docType", "how_to");
        m.put("riskLevel", "high");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String SAFE_MDX = "# Tracing\n\nEnable tracing in the starter.";
    private static final String SAFE = "A Spring Boot starter that auto-configures tracing.";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredFindings() throws Exception {
        String structured = "SUMMARY:\nMostly good.\n\n"
                + "VERDICT:\nAPPROVE_WITH_CHANGES\n\n"
                + "REVIEW:\nValid MDX with one unverified claim.\n\n"
                + "FINDINGS:\n- severity: HIGH\n  category: claims\n  title: Unverified version\n"
                + "  details: version 1.0.0 not in context\n  recommendation: confirm version\n\n"
                + "MISSING_SECTIONS:\n- Prerequisites\n\n"
                + "INCORRECT_OR_UNVERIFIED_CLAIMS:\n- version 1.0.0\n\n"
                + "MDX_ISSUES:\n- Tabs imported but unused\n\n"
                + "VALIDATION_CHECKLIST:\n- run docusaurus build\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE_MDX, SAFE, "Java 21"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "verdict", "review", "findings",
                "missingSections", "incorrectOrUnverifiedClaims", "mdxIssues", "styleIssues",
                "exampleIssues", "suggestedEdits", "validationChecklist", "risks", "safetyNotes",
                "assumptions", "truncated", "inputTokenEstimate", "outputTokenEstimate", "model",
                "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("verdict").asText()).isEqualTo("APPROVE_WITH_CHANGES");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");

        JsonNode findings = node.path("findings");
        assertThat(findings.isArray()).isTrue();
        assertThat(findings).hasSize(1);
        JsonNode f = findings.get(0);
        assertThat(f.path("severity").asText()).isEqualTo("HIGH");
        assertThat(f.path("category").asText()).isEqualTo("claims");
        assertThat(f.path("title").asText()).isEqualTo("Unverified version");
        assertThat(node.path("missingSections")).hasSize(1);
        assertThat(node.path("validationChecklist")).hasSize(1);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE_MDX, SAFE, "Java 21"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInLibraryContextBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args(SAFE_MDX, "api_key=ABC123SECRETVALUE", "c"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInConstraintsBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args(SAFE_MDX, SAFE, "please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }
}
