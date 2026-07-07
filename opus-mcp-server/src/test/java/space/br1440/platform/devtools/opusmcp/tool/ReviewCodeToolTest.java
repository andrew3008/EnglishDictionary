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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewCodeOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewFinding;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewCodeToolTest {

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

    private ReviewCodeTool tool(OpusClient client) {
        return new ReviewCodeTool(
                config(), client, new ReviewPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> args(String code, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review this method for correctness");
        m.put("language", "java");
        m.put("code", code);
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("reviewFocus", "all");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            The method is mostly correct but lacks overflow handling.

            REVIEW:
            The add method returns the sum of two ints.
            It does not guard against integer overflow.

            FINDINGS:
            - severity: HIGH
              category: correctness
              title: Integer overflow not handled
              details: add(a,b) can overflow for large inputs.
              recommendation: Use Math.addExact or validate inputs.
            - severity: LOW
              category: style
              title: Missing Javadoc
              details: Public method has no documentation.
              recommendation: Add a short Javadoc.

            RISKS:
            - Overflow can produce incorrect results

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - Inputs are 32-bit ints

            TESTS_TO_RUN:
            - add(Integer.MAX_VALUE, 1)
            """;

    @Test
    void okResponseParsesSummaryReviewAndFindings() {
        ReviewCodeOutput out = tool(new FakeOpusClient(STRUCTURED))
                .handle(args("public static int add(int a,int b){return a+b;}", "no repo context"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("overflow handling");
        assertThat(out.review()).contains("returns the sum").doesNotContain("SUMMARY:");
        assertThat(out.findings()).hasSize(2);

        ReviewFinding first = out.findings().get(0);
        assertThat(first.severity()).isEqualTo("HIGH");
        assertThat(first.category()).isEqualTo("correctness");
        assertThat(first.title()).isEqualTo("Integer overflow not handled");
        assertThat(first.details()).contains("overflow");
        assertThat(first.recommendation()).contains("Math.addExact");

        assertThat(out.risks()).containsExactly("Overflow can produce incorrect results");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("Inputs are 32-bit ints");
        assertThat(out.testsToRun()).containsExactly("add(Integer.MAX_VALUE, 1)");
        assertThat(out.inputTokenEstimate()).isEqualTo(13);
        assertThat(out.outputTokenEstimate()).isEqualTo(9);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void findingsWithUnknownSeverityFallBackToDefaults() {
        String text = "REVIEW:\nok\n\nFINDINGS:\n- severity: catastrophic\n  category: bogus\n"
                + "  title: weird\n  details: d\n  recommendation: r\n";
        ReviewCodeOutput out = tool(new FakeOpusClient(text))
                .handle(args("int x=1;", ""));
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("LOW");
        assertThat(out.findings().get(0).category()).isEqualTo("other");
    }

    @Test
    void malformedFindingDoesNotAbortParsingOfOthers() {
        String text = "REVIEW:\nok\n\nFINDINGS:\n- just a bare bullet finding\n"
                + "- severity: MEDIUM\n  title: real finding\n";
        ReviewCodeOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.findings()).hasSize(2);
        assertThat(out.findings().get(0).title()).isEqualTo("just a bare bullet finding");
        assertThat(out.findings().get(1).severity()).isEqualTo("MEDIUM");
        assertThat(out.findings().get(1).title()).isEqualTo("real finding");
    }

    @Test
    void nonCompliantResponseStillReturnsReviewText() {
        String text = "Here is my freeform review without any sections at all.";
        ReviewCodeOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("freeform review");
        assertThat(out.findings()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review");
        m.put("language", "java");
        m.put("code", ""); // blank code
        m.put("reviewFocus", "all");
        m.put("riskLevel", "low");
        m.put("outputFormat", "markdown");
        ReviewCodeOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void codeIsForwardedToModelPrompt() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nok\n");
        tool(client).handle(args("public int q(){return 42;}", "no repo context"));
        assertThat(client.last.get().userPrompt()).contains("public int q(){return 42;}");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
    }
}
