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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewGradleBuildPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GradleFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewGradleBuildOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewGradleBuildToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 71, 44);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ReviewGradleBuildTool tool(OpusClient client) {
        return new ReviewGradleBuildTool(
                config(), client, new ReviewGradleBuildPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review this Java library build for dependency hygiene and config cache");
        m.put("buildFilesContext", "plugins { id 'java-library' }\ndependencies { implementation 'com.google.guava:guava:31.0-jre' }");
        m.put("settingsContext", "rootProject.name = 'lib'");
        m.put("versionCatalogContext", "[versions]\nguava = \"31.0-jre\"");
        m.put("gradlePropertiesContext", "org.gradle.caching=true");
        m.put("buildLogicContext", "");
        m.put("dependencyContext", "single-module library");
        m.put("buildFailureLogs", "");
        m.put("constraints", "Java 21; Gradle 8.x");
        m.put("projectType", "java_library");
        m.put("gradleDsl", "groovy");
        m.put("reviewFocus", "all");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            The build hardcodes a dependency version and is not configuration-cache verified.

            VERDICT:
            APPROVE_WITH_CHANGES

            REVIEW:
            The build applies java-library correctly but declares guava with a literal version instead of the catalog.

            FINDINGS:
            - severity: HIGH
              category: dependency_management
              title: Hardcoded dependency version
              details: guava version is duplicated outside the version catalog
              recommendation: Use libs.guava from the version catalog
            - severity: MEDIUM
              category: configuration_cache
              title: Configuration cache not asserted
              details: No org.gradle.configuration-cache=true present
              recommendation: Enable and verify configuration cache compatibility

            CONFIGURATION_CACHE_ISSUES:
            - configuration cache flag is not enabled

            DEPENDENCY_ISSUES:
            - guava version duplicated outside the catalog

            PLUGIN_ISSUES:
            - none observed

            TASK_GRAPH_ISSUES:
            - none observed

            MULTI_MODULE_ISSUES:
            - single module; not applicable

            TEST_SETUP_ISSUES:
            - no test dependencies declared

            PUBLISHING_ISSUES:
            - no publishing configuration present

            PERFORMANCE_ISSUES:
            - build cache enabled but not verified

            SECURITY_ISSUES:
            - no dependency verification configured

            COMPATIBILITY_RISKS:
            - guava 31.0 is older than current

            RECOMMENDED_CHECKS:
            - run with --configuration-cache

            SUGGESTED_CHANGES:
            - move guava to libs.versions.toml

            OPEN_QUESTIONS:
            - is this library published anywhere?

            RISKS:
            - version drift between catalog and build

            SAFETY_NOTES:
            - no secrets in build files

            ASSUMPTIONS:
            - Gradle 8.x is used
            """;

    @Test
    void okResponseParsesAllSections() {
        ReviewGradleBuildOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("not configuration-cache verified");
        assertThat(out.verdict()).isEqualTo("APPROVE_WITH_CHANGES");
        assertThat(out.review()).contains("java-library");

        assertThat(out.findings()).hasSize(2);
        GradleFinding f1 = out.findings().get(0);
        assertThat(f1.severity()).isEqualTo("HIGH");
        assertThat(f1.category()).isEqualTo("dependency_management");
        assertThat(f1.title()).isEqualTo("Hardcoded dependency version");
        assertThat(out.findings().get(1).category()).isEqualTo("configuration_cache");

        assertThat(out.configurationCacheIssues()).hasSize(1);
        assertThat(out.dependencyIssues()).hasSize(1);
        assertThat(out.pluginIssues()).hasSize(1);
        assertThat(out.taskGraphIssues()).hasSize(1);
        assertThat(out.multiModuleIssues()).hasSize(1);
        assertThat(out.testSetupIssues()).hasSize(1);
        assertThat(out.publishingIssues()).hasSize(1);
        assertThat(out.performanceIssues()).hasSize(1);
        assertThat(out.securityIssues()).hasSize(1);
        assertThat(out.compatibilityRisks()).hasSize(1);
        assertThat(out.recommendedChecks()).containsExactly("run with --configuration-cache");
        assertThat(out.suggestedChanges()).containsExactly("move guava to libs.versions.toml");
        assertThat(out.openQuestions()).hasSize(1);
        assertThat(out.risks()).hasSize(1);
        assertThat(out.safetyNotes()).hasSize(1);
        assertThat(out.assumptions()).hasSize(1);
        assertThat(out.inputTokenEstimate()).isEqualTo(71);
        assertThat(out.outputTokenEstimate()).isEqualTo(44);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void findingWithUnknownSeverityFallsBackToMedium() {
        String text = "FINDINGS:\n- severity: catastrophic\n  category: wat\n  title: Foo\n";
        ReviewGradleBuildOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("MEDIUM");
        assertThat(out.findings().get(0).category()).isEqualTo("other");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInReview() {
        String text = "Here is a freeform note with no recognizable sections at all.";
        ReviewGradleBuildOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("freeform note");
        assertThat(out.findings()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("buildFilesContext", ""); // blank
        ReviewGradleBuildOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("gradleDsl", "not-a-dsl");
        ReviewGradleBuildOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void buildFilesAreForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nok\n");
        tool(client).handle(args());
        assertThat(client.last.get().userPrompt()).contains("java-library");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("recommend build changes as text only");
    }
}
