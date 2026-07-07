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
import space.br1440.platform.devtools.opusmcp.tool.dto.TestFinding;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTestsToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 61, 37);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ReviewTestsTool tool(OpusClient client) {
        return new ReviewTestsTool(
                config(), client, new ReviewTestsPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review this service unit test for correctness and flakiness");
        m.put("language", "java");
        m.put("testCode", "@Test void addsUser() { service.add(\"a\"); assertTrue(true); }");
        m.put("productionContext", "UserService.add(String) persists a user");
        m.put("testIntent", "Verify that add persists a user and rejects duplicates");
        m.put("failureLogs", "");
        m.put("dependenciesContext", "JUnit 5, Mockito, AssertJ");
        m.put("constraints", "Java 21");
        m.put("testFramework", "junit5");
        m.put("testType", "unit");
        m.put("reviewFocus", "all");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            The test asserts a constant and does not verify persistence behavior.

            VERDICT:
            REQUEST_CHANGES

            REVIEW:
            The test calls service.add but only asserts true, so it never verifies the intended behavior.

            FINDINGS:
            - severity: HIGH
              category: assertions
              title: Tautological assertion
              details: assertTrue(true) always passes
              recommendation: Assert the persisted user is retrievable
            - severity: MEDIUM
              category: coverage
              title: Missing duplicate-rejection test
              details: The duplicate path is never exercised
              recommendation: Add a test that adds the same user twice

            COVERAGE_GAPS:
            - Duplicate user rejection is untested

            ASSERTION_ISSUES:
            - assertTrue(true) verifies nothing

            FLAKINESS_RISKS:
            - None observed in this synchronous test

            MOCKING_ISSUES:
            - No verification of service interactions

            TEST_DATA_ISSUES:
            - Hardcoded user "a" is not meaningful

            INTEGRATION_BOUNDARY_ISSUES:
            - None; this is a unit test

            MAINTAINABILITY_ISSUES:
            - Test name does not describe behavior

            SUGGESTED_TEST_CASES:
            - addPersistsUserThenItIsRetrievable
            - addRejectsDuplicateUser

            CI_READINESS_CHECKS:
            - Ensure the test fails when persistence is broken

            OPEN_QUESTIONS:
            - Does add throw or return false on duplicates?

            RISKS:
            - False confidence from a passing but empty test

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - UserService exposes a retrieval method
            """;

    @Test
    void okResponseParsesAllSections() {
        ReviewTestsOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("does not verify persistence");
        assertThat(out.verdict()).isEqualTo("REQUEST_CHANGES");
        assertThat(out.review()).contains("only asserts true");

        assertThat(out.findings()).hasSize(2);
        TestFinding f1 = out.findings().get(0);
        assertThat(f1.severity()).isEqualTo("HIGH");
        assertThat(f1.category()).isEqualTo("assertions");
        assertThat(f1.title()).isEqualTo("Tautological assertion");
        assertThat(f1.recommendation()).contains("retrievable");
        assertThat(out.findings().get(1).category()).isEqualTo("coverage");

        assertThat(out.coverageGaps()).containsExactly("Duplicate user rejection is untested");
        assertThat(out.assertionIssues()).containsExactly("assertTrue(true) verifies nothing");
        assertThat(out.flakinessRisks()).hasSize(1);
        assertThat(out.mockingIssues()).hasSize(1);
        assertThat(out.testDataIssues()).hasSize(1);
        assertThat(out.integrationBoundaryIssues()).hasSize(1);
        assertThat(out.maintainabilityIssues()).hasSize(1);
        assertThat(out.suggestedTestCases()).containsExactly(
                "addPersistsUserThenItIsRetrievable", "addRejectsDuplicateUser");
        assertThat(out.ciReadinessChecks()).hasSize(1);
        assertThat(out.openQuestions()).hasSize(1);
        assertThat(out.risks()).hasSize(1);
        assertThat(out.safetyNotes()).hasSize(1);
        assertThat(out.assumptions()).hasSize(1);
        assertThat(out.inputTokenEstimate()).isEqualTo(61);
        assertThat(out.outputTokenEstimate()).isEqualTo(37);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void findingWithUnknownSeverityFallsBackToMedium() {
        String text = "FINDINGS:\n- severity: catastrophic\n  category: wat\n  title: Foo\n";
        ReviewTestsOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("MEDIUM");
        assertThat(out.findings().get(0).category()).isEqualTo("other");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInReview() {
        String text = "Here is a freeform note with no recognizable sections at all.";
        ReviewTestsOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("freeform note");
        assertThat(out.findings()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("testCode", ""); // blank
        ReviewTestsOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("testFramework", "not-a-framework");
        ReviewTestsOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void testCodeIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nok\n");
        tool(client).handle(args());
        assertThat(client.last.get().userPrompt()).contains("assertTrue(true)");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("recommend test changes as text only");
    }
}
