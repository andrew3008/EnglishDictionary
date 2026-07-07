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
import space.br1440.platform.devtools.opusmcp.prompt.WriteMdxDocPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.WriteMdxDocTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code write_mdx_doc_with_opus} at the serialized-JSON boundary,
 * using a deterministic fake model client (no network). Verifies stable output keys, MDX content
 * fidelity, no API key leakage, and pre-call guard behavior.
 */
class WriteMdxDocJsonPipelineTest {

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

    private WriteMdxDocTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new WriteMdxDocTool(
                config, client, new WriteMdxDocPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String libraryContext, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Draft a how-to for enabling tracing");
        m.put("docSubject", "Tracing Starter");
        m.put("targetAudience", "application_developers");
        m.put("libraryContext", libraryContext);
        m.put("constraints", constraints);
        m.put("docType", "how_to");
        m.put("outputFormat", "mdx_page");
        m.put("riskLevel", "high");
        return m;
    }

    private static final String SAFE = "A Spring Boot starter that auto-configures tracing.";

    @Test
    void serializedOutputHasStableSchemaKeysAndMdxContent() throws Exception {
        String structured = "SUMMARY:\nA how-to.\n\n"
                + "FRONT_MATTER:\n---\nid: tracing\n---\n\n"
                + "IMPORTS:\n- import Tabs from '@theme/Tabs';\n\n"
                + "MDX_CONTENT:\n# Tracing\n\n```properties\nplatform.tracing.enabled=true\n```\n\n"
                + "OUTLINE:\n- Overview\n\n"
                + "CLAIMS_TO_VERIFY:\n- default sampling rate\n\n"
                + "VALIDATION_CHECKLIST:\n- run docusaurus build\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "frontMatter", "imports", "mdxContent",
                "outline", "examples", "admonitions", "assetsNeeded", "linksToAdd", "claimsToVerify",
                "validationChecklist", "risks", "safetyNotes", "assumptions", "truncated",
                "inputTokenEstimate", "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(node.path("frontMatter").asText()).contains("id: tracing");
        assertThat(node.path("mdxContent").asText()).contains("platform.tracing.enabled=true");

        JsonNode imports = node.path("imports");
        assertThat(imports.isArray()).isTrue();
        assertThat(imports).hasSize(1);
        assertThat(node.path("outline")).hasSize(1);
        assertThat(node.path("claimsToVerify")).hasSize(1);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("MDX_CONTENT:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInLibraryContextBlocksBeforeModelCallAtJsonBoundary() throws Exception {
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
