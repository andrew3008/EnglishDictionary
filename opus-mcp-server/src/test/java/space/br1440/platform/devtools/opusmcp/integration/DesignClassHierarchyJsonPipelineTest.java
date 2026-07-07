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
import space.br1440.platform.devtools.opusmcp.prompt.DesignClassHierarchyPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.DesignClassHierarchyTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code design_class_hierarchy_with_opus} at the serialized-JSON
 * boundary, using a deterministic fake model client (no network). Verifies stable output keys,
 * structured proposedTypes/relationships, no API key leakage, and pre-call guard behavior.
 */
class DesignClassHierarchyJsonPipelineTest {

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

    private DesignClassHierarchyTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new DesignClassHierarchyTool(
                config, client, new DesignClassHierarchyPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String domainContext, String packageContext) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Design an extensible payment gateway hierarchy");
        m.put("language", "java");
        m.put("domainContext", domainContext);
        m.put("existingTypes", "interface PaymentGateway {}");
        m.put("packageContext", packageContext);
        m.put("constraints", "Java 21");
        m.put("designGoal", "extensibility");
        m.put("scope", "module");
        m.put("architectureStyle", "clean_architecture");
        m.put("riskLevel", "high");
        m.put("outputFormat", "design_proposal");
        return m;
    }

    private static final String SAFE_CONTEXT = "A payments domain with several providers.";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredTypesAndRelationships() throws Exception {
        String structured = "SUMMARY:\nExtensible gateway hierarchy.\n\n"
                + "DESIGN_OVERVIEW:\nUse a gateway interface with provider implementations.\n\n"
                + "PROPOSED_TYPES:\n- name: PaymentGateway\n  kind: interface\n"
                + "  package: space.example.payments\n  responsibility: Abstract a provider\n"
                + "  publicApi: charge(Money), refund(TxnId)\n  collaborators: Money\n  notes: keep small\n\n"
                + "RELATIONSHIPS:\n- from: StripeGateway\n  to: PaymentGateway\n  type: implements\n"
                + "  reason: provider impl\n\n"
                + "PACKAGE_PLAN:\n- space.example.payments\n\n"
                + "IMPLEMENTATION_SLICES:\n- Define interface\n\n"
                + "TESTS_TO_ADD:\n- PaymentGatewayContractTest\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE_CONTEXT, "space.example.payments"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "designOverview", "proposedTypes",
                "relationships", "packagePlan", "implementationSlices", "extensionPoints",
                "designAlternatives", "testsToAdd", "risks", "antiPatternsToAvoid", "safetyNotes",
                "assumptions", "truncated", "inputTokenEstimate", "outputTokenEstimate", "model",
                "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");

        JsonNode types = node.path("proposedTypes");
        assertThat(types.isArray()).isTrue();
        assertThat(types).hasSize(1);
        JsonNode t0 = types.get(0);
        assertThat(t0.path("name").asText()).isEqualTo("PaymentGateway");
        assertThat(t0.path("kind").asText()).isEqualTo("interface");
        assertThat(t0.path("packageName").asText()).isEqualTo("space.example.payments");
        assertThat(t0.path("publicApi").isArray()).isTrue();
        assertThat(t0.path("publicApi")).hasSize(2);

        JsonNode rels = node.path("relationships");
        assertThat(rels.isArray()).isTrue();
        assertThat(rels).hasSize(1);
        JsonNode r0 = rels.get(0);
        assertThat(r0.path("from").asText()).isEqualTo("StripeGateway");
        assertThat(r0.path("to").asText()).isEqualTo("PaymentGateway");
        assertThat(r0.path("type").asText()).isEqualTo("implements");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("DESIGN_OVERVIEW:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE_CONTEXT, "space.example.payments"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInDomainContextBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args("api_key=ABC123SECRETVALUE", "pkg"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void denyListInPackageContextBlocksBeforeModelCallAtJsonBoundary() throws Exception {
        FakeOpusClient client = new FakeOpusClient("unused");
        String json = tool(client).handleAsJson(args(SAFE_CONTEXT, "please read .env"));
        assertThat(mapper.readTree(json).path("status").asText()).isEqualTo("REFUSED_UNSAFE");
        assertThat(client.calls.get()).isZero();
    }
}
