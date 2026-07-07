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
import space.br1440.platform.devtools.opusmcp.prompt.ExplainDiffPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.DiffFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.ExplainDiffOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.MergeRecommendation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainDiffToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 17, 11);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ExplainDiffTool tool(OpusClient client) {
        return new ExplainDiffTool(
                config(), client, new ExplainDiffPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String diff, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Explain this diff and review correctness");
        m.put("language", "java");
        m.put("diff", diff);
        m.put("context", context);
        m.put("constraints", "Java 21, preserve behavior");
        m.put("diffFormat", "unified_diff");
        m.put("analysisFocus", "correctness");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "diff_explanation");
        return m;
    }

    private static final String SAMPLE_DIFF =
            "--- a/Calc.java\n+++ b/Calc.java\n@@ -1 +1 @@\n-return a+b;\n+return Math.addExact(a,b);";

    private static final String STRUCTURED = """
            SUMMARY:
            Switches addition to overflow-checked arithmetic.

            EXPLANATION:
            The change replaces a+b with Math.addExact, which throws on overflow.

            CHANGED_FILES:
            - Calc.java

            BEHAVIOR_CHANGES:
            - Throws ArithmeticException on integer overflow instead of wrapping

            FINDINGS:
            - severity: HIGH
              category: correctness
              title: Overflow now throws
              details: Callers relying on wraparound will break.
              recommendation: Confirm callers handle ArithmeticException.
            - severity: LOW
              category: tests
              title: Add overflow tests
              details: No test currently covers overflow.
              recommendation: Add a boundary test.

            RISKS:
            - Behavior change may surface as new runtime exceptions

            TESTS_TO_RUN:
            - ./gradlew test --tests CalcTest

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - add is used in arithmetic-sensitive paths

            MERGE_RECOMMENDATION:
            APPROVE_WITH_CHANGES
            """;

    @Test
    void okResponseParsesAllSections() {
        ExplainDiffOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args(SAMPLE_DIFF, "no repo"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("overflow-checked");
        assertThat(out.explanation()).contains("Math.addExact").doesNotContain("EXPLANATION:");
        assertThat(out.changedFiles()).containsExactly("Calc.java");
        assertThat(out.behaviorChanges()).containsExactly(
                "Throws ArithmeticException on integer overflow instead of wrapping");
        assertThat(out.findings()).hasSize(2);

        DiffFinding first = out.findings().get(0);
        assertThat(first.severity()).isEqualTo("HIGH");
        assertThat(first.category()).isEqualTo("correctness");
        assertThat(first.title()).isEqualTo("Overflow now throws");
        assertThat(first.details()).contains("wraparound");
        assertThat(first.recommendation()).contains("ArithmeticException");

        assertThat(out.findings().get(1).category()).isEqualTo("tests");
        assertThat(out.risks()).containsExactly("Behavior change may surface as new runtime exceptions");
        assertThat(out.testsToRun()).containsExactly("./gradlew test --tests CalcTest");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("add is used in arithmetic-sensitive paths");
        assertThat(out.mergeRecommendation()).isEqualTo(MergeRecommendation.APPROVE_WITH_CHANGES);
        assertThat(out.inputTokenEstimate()).isEqualTo(17);
        assertThat(out.outputTokenEstimate()).isEqualTo(11);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void findingsWithUnknownSeverityAndCategoryFallBackToDefaults() {
        String text = "EXPLANATION:\ne\n\nFINDINGS:\n- severity: catastrophic\n  category: bogus\n"
                + "  title: weird\n  details: d\n";
        ExplainDiffOutput out = tool(new FakeOpusClient(text)).handle(args(SAMPLE_DIFF, ""));
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("LOW");
        assertThat(out.findings().get(0).category()).isEqualTo("other");
    }

    @Test
    void malformedFindingDoesNotAbortParsingOfOthers() {
        String text = "EXPLANATION:\ne\n\nFINDINGS:\n- just a bare bullet finding\n"
                + "- severity: HIGH\n  title: real finding\n";
        ExplainDiffOutput out = tool(new FakeOpusClient(text)).handle(args(SAMPLE_DIFF, ""));
        assertThat(out.findings()).hasSize(2);
        assertThat(out.findings().get(0).title()).isEqualTo("just a bare bullet finding");
        assertThat(out.findings().get(1).title()).isEqualTo("real finding");
        assertThat(out.findings().get(1).severity()).isEqualTo("HIGH");
    }

    @Test
    void unknownMergeRecommendationFallsBackToNeedsMoreContext() {
        String text = "EXPLANATION:\ne\n\nMERGE_RECOMMENDATION:\nLGTM SHIP IT\n";
        ExplainDiffOutput out = tool(new FakeOpusClient(text)).handle(args(SAMPLE_DIFF, ""));
        assertThat(out.mergeRecommendation()).isEqualTo(MergeRecommendation.NEEDS_MORE_CONTEXT);
    }

    @Test
    void nonCompliantResponseStillReturnsTextInExplanation() {
        String text = "Here is a freeform explanation with no recognizable sections at all.";
        ExplainDiffOutput out = tool(new FakeOpusClient(text)).handle(args(SAMPLE_DIFF, ""));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.explanation()).contains("freeform explanation");
        assertThat(out.findings()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Explain");
        m.put("language", "java");
        m.put("diff", ""); // blank diff
        m.put("diffFormat", "unified_diff");
        m.put("analysisFocus", "correctness");
        m.put("riskLevel", "low");
        m.put("outputFormat", "diff_explanation");
        ExplainDiffOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args(SAMPLE_DIFF, "");
        m.put("diffFormat", "not-a-format");
        ExplainDiffOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void diffIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("EXPLANATION:\nok\n");
        tool(client).handle(args(SAMPLE_DIFF, "no repo context"));
        assertThat(client.last.get().userPrompt()).contains("Math.addExact(a,b)");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("do not apply patches");
        assertThat(client.last.get().systemPrompt().toLowerCase())
                .contains("untrusted data");
    }
}
