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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewTestsOutput;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code review_tests_with_opus}: representative review shapes (per
 * framework/test type) and adversarial model-response shapes must never crash and must degrade
 * gracefully.
 */
class ReviewTestsParserTest {

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

    private ReviewTestsTool tool(String modelText, int maxOutputChars) {
        return new ReviewTestsTool(
                config(), new FakeOpusClient(modelText), new ReviewTestsPromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String language, String framework, String testType,
            String failureLogs) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review tests");
        m.put("language", language);
        m.put("testCode", "some test code");
        m.put("testIntent", "verify behavior");
        m.put("failureLogs", failureLogs);
        m.put("testFramework", framework);
        m.put("testType", testType);
        m.put("reviewFocus", "all");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private ReviewTestsOutput run(String modelText, String language, String framework,
            String testType) {
        return tool(modelText, 50_000).handle(args(language, framework, testType, ""));
    }

    @Test
    void junit5UnitReviewParses() {
        String text = "FINDINGS:\n- severity: HIGH\n  category: assertions\n  title: weak assert\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "unit");
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("HIGH");
    }

    @Test
    void springBootIntegrationReviewParses() {
        String text = "REVIEW:\n@SpringBootTest loads the full context; consider a slice.\n"
                + "INTEGRATION_BOUNDARY_ISSUES:\n- full context is heavy\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "integration");
        assertThat(out.integrationBoundaryIssues()).containsExactly("full context is heavy");
    }

    @Test
    void testcontainersReviewParses() {
        String text = "FLAKINESS_RISKS:\n- container startup not awaited\n"
                + "CI_READINESS_CHECKS:\n- ensure Docker available on CI\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "integration");
        assertThat(out.flakinessRisks()).hasSize(1);
        assertThat(out.ciReadinessChecks()).hasSize(1);
    }

    @Test
    void grpcKafkaPostgresRedisReviewParses() {
        String text = "MOCKING_ISSUES:\n- real Kafka broker mocked away loses contract\n"
                + "INTEGRATION_BOUNDARY_ISSUES:\n- PostgreSQL schema not migrated in test\n"
                + "- Redis eviction not covered\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "integration");
        assertThat(out.mockingIssues()).hasSize(1);
        assertThat(out.integrationBoundaryIssues()).hasSize(2);
    }

    @Test
    void goTestReviewParses() {
        String text = "COVERAGE_GAPS:\n- error path not tested\n";
        ReviewTestsOutput out = run(text, "go", "go_testing", "unit");
        assertThat(out.coverageGaps()).containsExactly("error path not tested");
    }

    @Test
    void kotlinKotestReviewParses() {
        String text = "MAINTAINABILITY_ISSUES:\n- spec is too long\n";
        ReviewTestsOutput out = run(text, "kotlin", "kotest", "unit");
        assertThat(out.maintainabilityIssues()).containsExactly("spec is too long");
    }

    @Test
    void sqlMigrationTestReviewParses() {
        String text = "TEST_DATA_ISSUES:\n- migration test uses production-like data\n";
        ReviewTestsOutput out = run(text, "sql", "unknown", "integration");
        assertThat(out.testDataIssues()).hasSize(1);
    }

    @Test
    void flakyAsyncTestReviewParses() {
        String text = "FLAKINESS_RISKS:\n- Thread.sleep used instead of Awaitility\n"
                + "- timing-dependent assertion\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "integration");
        assertThat(out.flakinessRisks()).hasSize(2);
    }

    @Test
    void malformedResponseDoesNotCrashAndFallsBackToReview() {
        String text = "###garbage @@@ no sections here at all 123";
        ReviewTestsOutput out = run(text, "java", "junit5", "unit");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("garbage");
    }

    @Test
    void duplicatedSectionHeadingsDoNotCrash() {
        String text = "COVERAGE_GAPS:\n- first\nCOVERAGE_GAPS:\n- second\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "unit");
        assertThat(out.coverageGaps()).contains("second");
    }

    @Test
    void missingFindingsSectionYieldsEmptyList() {
        String text = "SUMMARY:\nok\nREVIEW:\nbody\nCOVERAGE_GAPS:\n- g1\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "unit");
        assertThat(out.findings()).isEmpty();
        assertThat(out.coverageGaps()).containsExactly("g1");
    }

    @Test
    void missingCoverageGapsSectionYieldsEmptyList() {
        String text = "REVIEW:\nbody\nFINDINGS:\n- severity: LOW\n  title: minor\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "unit");
        assertThat(out.coverageGaps()).isEmpty();
        assertThat(out.findings()).hasSize(1);
    }

    @Test
    void longResponseGetsTruncated() {
        StringBuilder sb = new StringBuilder("REVIEW:\n");
        for (int i = 0; i < 5000; i++) {
            sb.append("word").append(i).append(' ');
        }
        ReviewTestsOutput out = tool(sb.toString(), 200).handle(args("java", "junit5", "unit", ""));
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void markdownTableInFindingsDoesNotCrash() {
        String text = "FINDINGS:\n| severity | title |\n| --- | --- |\n| HIGH | weak assert |\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "unit");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void codeBlockInSuggestedTestCasesDoesNotCrash() {
        String text = "SUGGESTED_TEST_CASES:\n- addRejectsDuplicate\n```java\n@Test void x() {}\n```\n";
        ReviewTestsOutput out = run(text, "java", "junit5", "unit");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.suggestedTestCases()).contains("addRejectsDuplicate");
    }

    @Test
    void logsInContextAreReviewedWithoutCrashing() {
        String text = "REVIEW:\nThe failure log shows a NullPointerException at line 12.\n"
                + "FINDINGS:\n- severity: HIGH\n  category: correctness\n  title: NPE in setup\n";
        ReviewTestsOutput out = tool(text, 50_000).handle(
                args("java", "junit5", "unit", "java.lang.NullPointerException at Foo.java:12"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.findings()).hasSize(1);
    }
}
