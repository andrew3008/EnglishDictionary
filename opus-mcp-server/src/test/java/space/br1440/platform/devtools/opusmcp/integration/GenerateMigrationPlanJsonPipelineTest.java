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
import space.br1440.platform.devtools.opusmcp.prompt.GenerateMigrationPlanPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.GenerateMigrationPlanTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test for {@code generate_migration_plan_with_opus} at the serialized-JSON
 * boundary, using a deterministic fake model client (no network). Verifies stable output keys,
 * structured migration-slice fidelity, no API key leakage, and pre-call guard behavior.
 */
class GenerateMigrationPlanJsonPipelineTest {

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

    private GenerateMigrationPlanTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new GenerateMigrationPlanTool(
                config, client, new GenerateMigrationPlanPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String currentState, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Upgrade framework");
        m.put("language", "java");
        m.put("currentState", currentState);
        m.put("targetState", "Spring Boot 3.3");
        m.put("constraints", constraints);
        m.put("compatibilityMode", "preserve_api");
        m.put("migrationScope", "starter");
        m.put("migrationType", "framework_upgrade");
        m.put("riskLevel", "high");
        m.put("outputFormat", "migration_slices");
        return m;
    }

    private static final String SAFE = "Spring Boot 2.7 baseline";

    @Test
    void serializedOutputHasStableSchemaKeysAndStructuredSlices() throws Exception {
        String structured = "SUMMARY:\nStaged upgrade.\n\n"
                + "MIGRATION_OVERVIEW:\nSmall reversible slices.\n\n"
                + "MIGRATION_SLICES:\n- id: S1\n  title: Baseline\n  goal: lock behavior\n"
                + "  changes: pin versions, add gate\n  verification: build passes\n  risk: LOW\n"
                + "  rollback: revert pins\n\n"
                + "COMPATIBILITY_NOTES:\n- API preserved\n\n"
                + "TEST_PLAN:\n- migrate to JUnit 5\n\n"
                + "ROLLBACK_PLAN:\n- revert to 2.7 tag\n";
        FakeOpusClient client = new FakeOpusClient(structured);

        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        JsonNode node = mapper.readTree(json);

        for (String key : new String[] {"status", "summary", "migrationOverview", "migrationSlices",
                "compatibilityNotes", "breakingRisks", "dependencyChanges", "configurationChanges",
                "testPlan", "observabilityChecks", "rolloutPlan", "rollbackPlan", "docsUpdates",
                "openQuestions", "risks", "safetyNotes", "assumptions", "truncated",
                "inputTokenEstimate", "outputTokenEstimate", "model", "requestId"}) {
            assertThat(node.has(key)).as("missing key %s", key).isTrue();
        }
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("model").asText()).isEqualTo("claude-opus-4-8");

        JsonNode slices = node.path("migrationSlices");
        assertThat(slices.isArray()).isTrue();
        assertThat(slices).hasSize(1);
        JsonNode s = slices.get(0);
        assertThat(s.path("id").asText()).isEqualTo("S1");
        assertThat(s.path("title").asText()).isEqualTo("Baseline");
        assertThat(s.path("risk").asText()).isEqualTo("LOW");
        assertThat(s.path("changes")).hasSize(2);
        assertThat(s.path("verification")).hasSize(1);
        assertThat(node.path("compatibilityNotes")).hasSize(1);
        assertThat(node.path("rollbackPlan")).hasSize(1);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void serializedOutputNeverContainsApiKey() {
        FakeOpusClient client = new FakeOpusClient("MIGRATION_OVERVIEW:\nfine\n");
        String json = tool(client).handleAsJson(args(SAFE, "Java 21"));
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    void secretInCurrentStateBlocksBeforeModelCallAtJsonBoundary() throws Exception {
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
