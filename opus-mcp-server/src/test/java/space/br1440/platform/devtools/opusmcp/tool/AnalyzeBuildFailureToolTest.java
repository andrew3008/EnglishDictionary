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
import space.br1440.platform.devtools.opusmcp.prompt.AnalyzeBuildFailurePromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.AnalyzeBuildFailureOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.FixOption;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzeBuildFailureToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 23, 13);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private AnalyzeBuildFailureTool tool(OpusClient client) {
        return new AnalyzeBuildFailureTool(
                config(), client, new AnalyzeBuildFailurePromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String failureLog, String relevantCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Diagnose why the build fails");
        m.put("failureLog", failureLog);
        m.put("relevantCode", relevantCode);
        m.put("buildContext", "Gradle 8.7, Java 21");
        m.put("constraints", "Preserve behavior");
        m.put("failureType", "compile");
        m.put("language", "java");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "diagnosis");
        return m;
    }

    private static final String SAMPLE_LOG =
            "Calc.java:10: error: cannot find symbol\n  symbol:   method addExact(int,int)";

    private static final String STRUCTURED = """
            SUMMARY:
            Compilation fails because addExact is called on the wrong type.

            ROOT_CAUSE_HYPOTHESES:
            - Wrong import or missing Math qualifier
            - Method signature mismatch

            MOST_LIKELY_CAUSE:
            Math.addExact is referenced without importing Math statically.

            EVIDENCE:
            - "cannot find symbol" at Calc.java:10
            - symbol: method addExact(int,int)

            FIX_OPTIONS:
            - title: Qualify with Math
              description: Use Math.addExact instead of addExact.
              risk: LOW
              requiresCodeChange: true
              requiresDependencyChange: false
            - title: Add static import
              description: import static java.lang.Math.addExact;
              risk: LOW
              requiresCodeChange: true
              requiresDependencyChange: false

            MINIMAL_PATCH_SUGGESTION:
            ```java
            -return addExact(a, b);
            +return Math.addExact(a, b);
            ```

            TESTS_TO_RERUN:
            - ./gradlew test --tests CalcTest

            RISKS:
            - None significant

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - Math is the intended helper
            """;

    @Test
    void okResponseParsesAllSections() {
        AnalyzeBuildFailureOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args(SAMPLE_LOG, "code"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("Compilation fails");
        assertThat(out.rootCauseHypotheses()).hasSize(2);
        assertThat(out.mostLikelyCause()).contains("Math.addExact");
        assertThat(out.evidence()).anyMatch(e -> e.contains("cannot find symbol"));
        assertThat(out.fixOptions()).hasSize(2);

        FixOption first = out.fixOptions().get(0);
        assertThat(first.title()).isEqualTo("Qualify with Math");
        assertThat(first.description()).contains("Math.addExact");
        assertThat(first.risk()).isEqualTo("LOW");
        assertThat(first.requiresCodeChange()).isTrue();
        assertThat(first.requiresDependencyChange()).isFalse();

        assertThat(out.minimalPatchSuggestion()).contains("Math.addExact(a, b)");
        assertThat(out.testsToRerun()).containsExactly("./gradlew test --tests CalcTest");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("Math is the intended helper");
        assertThat(out.inputTokenEstimate()).isEqualTo(23);
        assertThat(out.outputTokenEstimate()).isEqualTo(13);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void fixOptionWithUnknownRiskFallsBackToMedium() {
        String text = "MOST_LIKELY_CAUSE:\nx\n\nFIX_OPTIONS:\n- title: t\n  description: d\n  risk: bogus\n";
        AnalyzeBuildFailureOutput out = tool(new FakeOpusClient(text)).handle(args(SAMPLE_LOG, ""));
        assertThat(out.fixOptions()).hasSize(1);
        assertThat(out.fixOptions().get(0).risk()).isEqualTo("MEDIUM");
    }

    @Test
    void malformedFixOptionDoesNotAbortParsingOfOthers() {
        String text = "FIX_OPTIONS:\n- just a bare bullet option\n- title: real option\n  risk: HIGH\n";
        AnalyzeBuildFailureOutput out = tool(new FakeOpusClient(text)).handle(args(SAMPLE_LOG, ""));
        assertThat(out.fixOptions()).hasSize(2);
        assertThat(out.fixOptions().get(0).title()).isEqualTo("just a bare bullet option");
        assertThat(out.fixOptions().get(1).title()).isEqualTo("real option");
        assertThat(out.fixOptions().get(1).risk()).isEqualTo("HIGH");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInMostLikelyCause() {
        String text = "Here is a freeform diagnosis with no recognizable sections at all.";
        AnalyzeBuildFailureOutput out = tool(new FakeOpusClient(text)).handle(args(SAMPLE_LOG, ""));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.mostLikelyCause()).contains("freeform diagnosis");
        assertThat(out.fixOptions()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Diagnose");
        m.put("failureLog", ""); // blank log
        m.put("failureType", "compile");
        m.put("language", "java");
        m.put("riskLevel", "low");
        m.put("outputFormat", "diagnosis");
        AnalyzeBuildFailureOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args(SAMPLE_LOG, "");
        m.put("failureType", "not-a-type");
        AnalyzeBuildFailureOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void failureLogIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("MOST_LIKELY_CAUSE:\nok\n");
        tool(client).handle(args(SAMPLE_LOG, "no repo context"));
        assertThat(client.last.get().userPrompt()).contains("cannot find symbol");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("do not apply patches");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
    }
}
