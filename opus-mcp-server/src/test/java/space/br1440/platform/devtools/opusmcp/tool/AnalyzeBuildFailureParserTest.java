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
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code analyze_build_failure_with_opus}: representative failure-log
 * categories and adversarial model-response shapes must never crash and must degrade gracefully.
 */
class AnalyzeBuildFailureParserTest {

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

    private AnalyzeBuildFailureTool tool(String modelText, int maxOutputChars) {
        return new AnalyzeBuildFailureTool(
                config(), new FakeOpusClient(modelText), new AnalyzeBuildFailurePromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String failureLog, String failureType) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Diagnose");
        m.put("failureLog", failureLog);
        m.put("failureType", failureType);
        m.put("language", "java");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "diagnosis");
        return m;
    }

    private AnalyzeBuildFailureOutput run(String modelText, String log, String type) {
        return tool(modelText, 20_000).handle(args(log, type));
    }

    @Test
    void compileErrorLogIsAnalyzed() {
        String resp = "SUMMARY:\nMissing symbol.\n\nMOST_LIKELY_CAUSE:\nUnresolved method.\n\n"
                + "EVIDENCE:\n- cannot find symbol\n";
        AnalyzeBuildFailureOutput out = run(resp,
                "A.java:3: error: cannot find symbol\n  symbol: method foo()", "compile");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.evidence()).anyMatch(e -> e.contains("cannot find symbol"));
    }

    @Test
    void junitFailureLogIsAnalyzed() {
        String resp = "MOST_LIKELY_CAUSE:\nAssertion mismatch.\n\nEVIDENCE:\n- expected 2 but was 3\n";
        AnalyzeBuildFailureOutput out = run(resp,
                "org.opentest4j.AssertionFailedError: expected: <2> but was: <3>", "test");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.mostLikelyCause()).contains("Assertion mismatch");
    }

    @Test
    void gradleTaskFailureLogIsAnalyzed() {
        String resp = "MOST_LIKELY_CAUSE:\nTask config error.\n\nFIX_OPTIONS:\n- title: Fix task\n";
        AnalyzeBuildFailureOutput out = run(resp,
                "Execution failed for task ':app:compileJava'.", "gradle");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.fixOptions()).hasSize(1);
    }

    @Test
    void checkstyleSpotbugsStaticAnalysisLogsAreAnalyzed() {
        String resp = "MOST_LIKELY_CAUSE:\nStyle violation.\n";
        assertThat(run(resp, "[ERROR] Foo.java:10: Line is longer than 100 characters", "checkstyle")
                .status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(run(resp, "H C HE: Foo defines equals but not hashCode", "spotbugs")
                .status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(run(resp, "Sonar: rule S106 violated", "static_analysis")
                .status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void runtimeStackTraceLogIsAnalyzed() {
        String resp = "MOST_LIKELY_CAUSE:\nNull dereference.\n\nEVIDENCE:\n- NullPointerException\n";
        AnalyzeBuildFailureOutput out = run(resp,
                "java.lang.NullPointerException\n\tat com.x.Foo.bar(Foo.java:42)", "runtime");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.evidence()).anyMatch(e -> e.contains("NullPointerException"));
    }

    @Test
    void malformedModelResponseDoesNotCrash() {
        AnalyzeBuildFailureOutput out = run("::::\n- \n#### \n```\nunterminated", "boom", "unknown");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.mostLikelyCause()).isNotNull();
    }

    @Test
    void duplicatedSectionHeadingsAreMerged() {
        String resp = "EVIDENCE:\n- first\n\nEVIDENCE:\n- second\n";
        AnalyzeBuildFailureOutput out = run(resp, "log", "unknown");
        assertThat(out.evidence()).containsExactly("first", "second");
    }

    @Test
    void missingFixOptionsSectionYieldsEmptyList() {
        String resp = "SUMMARY:\nx\n\nMOST_LIKELY_CAUSE:\ny\n\nEVIDENCE:\n- z\n";
        AnalyzeBuildFailureOutput out = run(resp, "log", "unknown");
        assertThat(out.fixOptions()).isEmpty();
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void longResponseIsTruncated() {
        StringBuilder big = new StringBuilder("MOST_LIKELY_CAUSE:\n");
        for (int i = 0; i < 5000; i++) {
            big.append("very long diagnosis line number ").append(i).append('\n');
        }
        AnalyzeBuildFailureOutput out = tool(big.toString(), 500).handle(args("log", "unknown"));
        assertThat(out.truncated()).isTrue();
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void markdownCodeBlockIsPreservedInMinimalPatchSuggestion() {
        String resp = "MINIMAL_PATCH_SUGGESTION:\n```java\n-int x = a + b;\n+int x = Math.addExact(a, b);\n```\n";
        AnalyzeBuildFailureOutput out = run(resp, "log", "compile");
        assertThat(out.minimalPatchSuggestion()).contains("```java");
        assertThat(out.minimalPatchSuggestion()).contains("Math.addExact(a, b)");
    }
}
