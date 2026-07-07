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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code generate_migration_plan_with_opus}: representative plan
 * shapes (one per migration type) and adversarial model-response shapes must never crash and must
 * degrade gracefully.
 */
class GenerateMigrationPlanParserTest {

    private static final class FakeOpusClient implements OpusClient {
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            return new OpusResponse(text, 5, 5);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private GenerateMigrationPlanTool tool(String modelText, int maxOutputChars) {
        return new GenerateMigrationPlanTool(
                config(), new FakeOpusClient(modelText), new GenerateMigrationPlanPromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String migrationType) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Plan a migration");
        m.put("language", "java");
        m.put("currentState", "current");
        m.put("targetState", "target");
        m.put("compatibilityMode", "preserve_api");
        m.put("migrationScope", "module");
        m.put("migrationType", migrationType);
        m.put("riskLevel", "medium");
        m.put("outputFormat", "migration_slices");
        return m;
    }

    private GenerateMigrationPlanOutput run(String modelText, String migrationType) {
        return tool(modelText, 50_000).handle(args(migrationType));
    }

    @Test
    void frameworkUpgradePlanParses() {
        String text = "MIGRATION_SLICES:\n- id: S1\n  title: Bump Spring Boot\n  risk: HIGH\n";
        GenerateMigrationPlanOutput out = run(text, "framework_upgrade");
        assertThat(out.migrationSlices()).hasSize(1);
        assertThat(out.migrationSlices().get(0).risk()).isEqualTo("HIGH");
    }

    @Test
    void apiMigrationPlanParses() {
        String text = "MIGRATION_SLICES:\n- id: A1\n  title: Replace deprecated API\n"
                + "  changes: swap method calls\n  risk: MEDIUM\n";
        GenerateMigrationPlanOutput out = run(text, "api_migration");
        assertThat(out.migrationSlices().get(0).changes()).containsExactly("swap method calls");
    }

    @Test
    void dependencyUpgradePlanParses() {
        String text = "DEPENDENCY_CHANGES:\n- lib 1.0 -> 2.0\n- drop unused dep\n";
        GenerateMigrationPlanOutput out = run(text, "dependency_upgrade");
        assertThat(out.dependencyChanges()).hasSize(2);
    }

    @Test
    void architectureMigrationPlanParses() {
        String text = "MIGRATION_OVERVIEW:\nMove to hexagonal architecture in slices.\n";
        GenerateMigrationPlanOutput out = run(text, "architecture_migration");
        assertThat(out.migrationOverview()).contains("hexagonal");
    }

    @Test
    void configurationMigrationPlanParses() {
        String text = "CONFIGURATION_CHANGES:\n- rename property a to b\n";
        GenerateMigrationPlanOutput out = run(text, "configuration_migration");
        assertThat(out.configurationChanges()).containsExactly("rename property a to b");
    }

    @Test
    void documentationMigrationPlanParses() {
        String text = "DOCS_UPDATES:\n- migrate docs to Docusaurus 3\n";
        GenerateMigrationPlanOutput out = run(text, "documentation_migration");
        assertThat(out.docsUpdates()).containsExactly("migrate docs to Docusaurus 3");
    }

    @Test
    void testMigrationPlanParses() {
        String text = "TEST_PLAN:\n- JUnit 4 to JUnit 5\n- adopt AssertJ\n";
        GenerateMigrationPlanOutput out = run(text, "test_migration");
        assertThat(out.testPlan()).hasSize(2);
    }

    @Test
    void buildMigrationPlanParses() {
        String text = "MIGRATION_SLICES:\n- id: B1\n  title: Gradle version catalog\n  risk: LOW\n";
        GenerateMigrationPlanOutput out = run(text, "build_migration");
        assertThat(out.migrationSlices().get(0).title()).isEqualTo("Gradle version catalog");
    }

    @Test
    void malformedResponseDoesNotCrashAndFallsBackToOverview() {
        String text = "###garbage @@@ no sections here at all 123";
        GenerateMigrationPlanOutput out = run(text, "unknown");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.migrationOverview()).contains("garbage");
    }

    @Test
    void duplicatedSectionHeadingsDoNotCrash() {
        String text = "TEST_PLAN:\n- first\nTEST_PLAN:\n- second\n";
        GenerateMigrationPlanOutput out = run(text, "test_migration");
        assertThat(out.testPlan()).contains("second");
    }

    @Test
    void missingMigrationSlicesSectionYieldsEmptyList() {
        String text = "SUMMARY:\nok\nMIGRATION_OVERVIEW:\nbody\nTEST_PLAN:\n- t1\n";
        GenerateMigrationPlanOutput out = run(text, "framework_upgrade");
        assertThat(out.migrationSlices()).isEmpty();
        assertThat(out.testPlan()).containsExactly("t1");
    }

    @Test
    void emptyRollbackPlanYieldsEmptyList() {
        String text = "MIGRATION_OVERVIEW:\nbody\nROLLBACK_PLAN:\n";
        GenerateMigrationPlanOutput out = run(text, "framework_upgrade");
        assertThat(out.rollbackPlan()).isEmpty();
    }

    @Test
    void longResponseGetsTruncated() {
        StringBuilder sb = new StringBuilder("MIGRATION_OVERVIEW:\n");
        for (int i = 0; i < 5000; i++) {
            sb.append("word").append(i).append(' ');
        }
        GenerateMigrationPlanOutput out = tool(sb.toString(), 200).handle(args("framework_upgrade"));
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void markdownTableInMigrationSlicesDoesNotCrash() {
        String text = "MIGRATION_SLICES:\n| id | title | risk |\n| --- | --- | --- |\n"
                + "| S1 | Baseline | LOW |\n";
        GenerateMigrationPlanOutput out = run(text, "framework_upgrade");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void codeBlockInOverviewIsPreserved() {
        String text = "MIGRATION_OVERVIEW:\nExample build file:\n```gradle\nplugins { id 'java' }\n```\n";
        GenerateMigrationPlanOutput out = run(text, "build_migration");
        assertThat(out.migrationOverview()).contains("plugins { id 'java' }");
    }

    @Test
    void codeBlockInSliceChangesDoesNotCrash() {
        String text = "MIGRATION_SLICES:\n- id: S1\n  title: edit build\n"
                + "  changes: update gradle\n```gradle\nimplementation 'x:y:1'\n```\n  risk: LOW\n";
        GenerateMigrationPlanOutput out = run(text, "build_migration");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.migrationSlices()).hasSize(1);
    }
}
