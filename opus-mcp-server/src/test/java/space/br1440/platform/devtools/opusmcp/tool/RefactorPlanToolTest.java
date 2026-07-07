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
import space.br1440.platform.devtools.opusmcp.prompt.RefactorPlanPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.RefactorPlanOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.RefactorStep;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RefactorPlanToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 13, 9);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private RefactorPlanTool tool(OpusClient client) {
        return new RefactorPlanTool(
                config(), client, new RefactorPlanPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String code, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Refactor add for readability");
        m.put("language", "java");
        m.put("code", code);
        m.put("context", context);
        m.put("constraints", "Java 21, no external libraries");
        m.put("refactorGoal", "readability");
        m.put("scope", "method");
        m.put("compatibilityMode", "preserve_behavior");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "refactor_plan");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            Small readability refactor preserving behavior.

            PLAN:
            Extract a helper and add overflow guard while keeping the public contract.

            STEPS:
            - id: RF-001
              title: Extract validation helper
              category: structure
              risk: LOW
              requiresBehaviorChange: false
              description: Move input checks into a private method.
              verification: Run existing unit tests.
            - id: RF-002
              title: Add overflow guard
              category: performance
              risk: HIGH
              requiresBehaviorChange: true
              description: Use Math.addExact.
              verification: Add overflow tests.

            AFFECTED_AREAS:
            - Calculator.add

            ROLLBACK_PLAN:
            Revert the commit; no schema or API changes.

            RISKS:
            - Overflow guard changes behavior for large inputs

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - add is a pure static method

            TESTS_TO_RUN:
            - ./gradlew test --tests CalculatorTest
            """;

    @Test
    void okResponseParsesAllSections() {
        RefactorPlanOutput out = tool(new FakeOpusClient(STRUCTURED))
                .handle(args("public static int add(int a,int b){return a+b;}", "no repo context"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("readability refactor");
        assertThat(out.plan()).contains("Extract a helper").doesNotContain("PLAN:");
        assertThat(out.steps()).hasSize(2);

        RefactorStep first = out.steps().get(0);
        assertThat(first.id()).isEqualTo("RF-001");
        assertThat(first.title()).isEqualTo("Extract validation helper");
        assertThat(first.category()).isEqualTo("structure");
        assertThat(first.risk()).isEqualTo("LOW");
        assertThat(first.requiresBehaviorChange()).isFalse();
        assertThat(first.description()).contains("private method");
        assertThat(first.verification()).contains("unit tests");

        RefactorStep second = out.steps().get(1);
        assertThat(second.risk()).isEqualTo("HIGH");
        assertThat(second.requiresBehaviorChange()).isTrue();
        assertThat(second.category()).isEqualTo("performance");

        assertThat(out.affectedAreas()).containsExactly("Calculator.add");
        assertThat(out.rollbackPlan()).contains("Revert the commit");
        assertThat(out.risks()).containsExactly("Overflow guard changes behavior for large inputs");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("add is a pure static method");
        assertThat(out.testsToRun()).containsExactly("./gradlew test --tests CalculatorTest");
        assertThat(out.inputTokenEstimate()).isEqualTo(13);
        assertThat(out.outputTokenEstimate()).isEqualTo(9);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void stepsWithUnknownRiskAndCategoryFallBackToDefaults() {
        String text = "PLAN:\nplan\n\nSTEPS:\n- id: RF-9\n  title: weird\n  category: bogus\n"
                + "  risk: catastrophic\n  description: d\n";
        RefactorPlanOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.steps()).hasSize(1);
        assertThat(out.steps().get(0).risk()).isEqualTo("MEDIUM");
        assertThat(out.steps().get(0).category()).isEqualTo("other");
    }

    @Test
    void malformedStepDoesNotAbortParsingOfOthers() {
        String text = "PLAN:\nplan\n\nSTEPS:\n- just a bare bullet step\n"
                + "- id: RF-2\n  title: real step\n  risk: HIGH\n";
        RefactorPlanOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.steps()).hasSize(2);
        assertThat(out.steps().get(0).title()).isEqualTo("just a bare bullet step");
        assertThat(out.steps().get(1).title()).isEqualTo("real step");
        assertThat(out.steps().get(1).risk()).isEqualTo("HIGH");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInPlan() {
        String text = "Here is a freeform refactoring idea without any sections at all.";
        RefactorPlanOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.plan()).contains("freeform refactoring idea");
        assertThat(out.steps()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Refactor");
        m.put("language", "java");
        m.put("code", ""); // blank code
        m.put("refactorGoal", "readability");
        m.put("scope", "method");
        m.put("compatibilityMode", "preserve_behavior");
        m.put("riskLevel", "low");
        m.put("outputFormat", "refactor_plan");
        RefactorPlanOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args("int x=1;", "");
        m.put("compatibilityMode", "not-a-mode");
        RefactorPlanOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void codeIsForwardedToModelPrompt() {
        FakeOpusClient client = new FakeOpusClient("PLAN:\nok\n");
        tool(client).handle(args("public int q(){return 42;}", "no repo context"));
        assertThat(client.last.get().userPrompt()).contains("public int q(){return 42;}");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("do not apply changes");
    }
}
