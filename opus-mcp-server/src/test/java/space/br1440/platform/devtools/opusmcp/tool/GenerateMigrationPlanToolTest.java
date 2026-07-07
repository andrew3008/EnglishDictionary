package space.br1440.platform.devtools.opusmcp.tool;

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
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateMigrationPlanOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.MigrationSlice;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateMigrationPlanToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 53, 31);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private GenerateMigrationPlanTool tool(OpusClient client) {
        return new GenerateMigrationPlanTool(
                config(), client, new GenerateMigrationPlanPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Upgrade the platform starter from Spring Boot 2.7 to 3.3");
        m.put("language", "java");
        m.put("currentState", "Spring Boot 2.7, javax.* imports, JUnit 4 tests");
        m.put("targetState", "Spring Boot 3.3, jakarta.* imports, JUnit 5 tests");
        m.put("migrationContext", "Gradle multi-module library starter");
        m.put("constraints", "Preserve public API; Java 21");
        m.put("compatibilityMode", "preserve_api");
        m.put("migrationScope", "starter");
        m.put("migrationType", "framework_upgrade");
        m.put("riskLevel", "high");
        m.put("outputFormat", "migration_slices");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            A staged Spring Boot 2.7 to 3.3 upgrade preserving the public API.

            MIGRATION_OVERVIEW:
            Migrate in small reversible slices: baseline, jakarta namespace, dependencies, tests.

            MIGRATION_SLICES:
            - id: S1
              title: Establish a green baseline
              goal: Lock current behavior before changes
              changes: Pin dependency versions, Add CI gate
              verification: Build passes, All tests green
              risk: LOW
              rollback: No code change; revert version pins
            - id: S2
              title: Migrate javax to jakarta
              goal: Move to jakarta.* namespace
              changes: Rewrite imports, Update annotations
              verification: Compile succeeds, Slice tests pass
              risk: HIGH
              rollback: Revert the namespace commit

            COMPATIBILITY_NOTES:
            - Public API is preserved across the upgrade

            BREAKING_RISKS:
            - jakarta namespace change is source-incompatible

            DEPENDENCY_CHANGES:
            - Spring Boot 2.7 -> 3.3

            CONFIGURATION_CHANGES:
            - Review deprecated application.properties keys

            TEST_PLAN:
            - Migrate JUnit 4 to JUnit 5

            OBSERVABILITY_CHECKS:
            - Verify Actuator endpoints still expose metrics

            ROLLOUT_PLAN:
            - Roll out behind a feature branch then canary

            ROLLBACK_PLAN:
            - Revert to the 2.7 tag if smoke fails

            DOCS_UPDATES:
            - Update the starter guide to Boot 3.3

            OPEN_QUESTIONS:
            - Are there third-party libs without jakarta support?

            RISKS:
            - Transitive dependencies may lag jakarta

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - Build is Gradle-based
            """;

    @Test
    void okResponseParsesAllSections() {
        GenerateMigrationPlanOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("Spring Boot 2.7 to 3.3");
        assertThat(out.migrationOverview()).contains("small reversible slices");

        assertThat(out.migrationSlices()).hasSize(2);
        MigrationSlice s1 = out.migrationSlices().get(0);
        assertThat(s1.id()).isEqualTo("S1");
        assertThat(s1.title()).isEqualTo("Establish a green baseline");
        assertThat(s1.goal()).isEqualTo("Lock current behavior before changes");
        assertThat(s1.changes()).containsExactly("Pin dependency versions", "Add CI gate");
        assertThat(s1.verification()).containsExactly("Build passes", "All tests green");
        assertThat(s1.risk()).isEqualTo("LOW");
        assertThat(s1.rollback()).contains("revert version pins");
        MigrationSlice s2 = out.migrationSlices().get(1);
        assertThat(s2.id()).isEqualTo("S2");
        assertThat(s2.risk()).isEqualTo("HIGH");

        assertThat(out.compatibilityNotes()).containsExactly("Public API is preserved across the upgrade");
        assertThat(out.breakingRisks()).containsExactly("jakarta namespace change is source-incompatible");
        assertThat(out.dependencyChanges()).containsExactly("Spring Boot 2.7 -> 3.3");
        assertThat(out.configurationChanges()).containsExactly("Review deprecated application.properties keys");
        assertThat(out.testPlan()).containsExactly("Migrate JUnit 4 to JUnit 5");
        assertThat(out.observabilityChecks()).containsExactly("Verify Actuator endpoints still expose metrics");
        assertThat(out.rolloutPlan()).containsExactly("Roll out behind a feature branch then canary");
        assertThat(out.rollbackPlan()).containsExactly("Revert to the 2.7 tag if smoke fails");
        assertThat(out.docsUpdates()).containsExactly("Update the starter guide to Boot 3.3");
        assertThat(out.openQuestions()).containsExactly("Are there third-party libs without jakarta support?");
        assertThat(out.risks()).containsExactly("Transitive dependencies may lag jakarta");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("Build is Gradle-based");
        assertThat(out.inputTokenEstimate()).isEqualTo(53);
        assertThat(out.outputTokenEstimate()).isEqualTo(31);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void sliceWithUnknownRiskFallsBackToMedium() {
        String text = "MIGRATION_SLICES:\n- id: S1\n  title: Foo\n  risk: catastrophic\n";
        GenerateMigrationPlanOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.migrationSlices()).hasSize(1);
        assertThat(out.migrationSlices().get(0).risk()).isEqualTo("MEDIUM");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInOverview() {
        String text = "Here is a freeform migration note with no recognizable sections at all.";
        GenerateMigrationPlanOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.migrationOverview()).contains("freeform migration note");
        assertThat(out.migrationSlices()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("currentState", ""); // blank
        GenerateMigrationPlanOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("migrationType", "not-a-type");
        GenerateMigrationPlanOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void stateIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("MIGRATION_OVERVIEW:\nok\n");
        tool(client).handle(args());
        assertThat(client.last.get().userPrompt()).contains("jakarta.* imports");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("recommend migration steps as text only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
    }
}
