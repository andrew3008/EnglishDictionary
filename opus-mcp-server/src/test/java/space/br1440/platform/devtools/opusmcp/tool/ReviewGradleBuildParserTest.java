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
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewGradleBuildOutput;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code review_gradle_build_with_opus}: representative review shapes
 * (per DSL / project type / build artifact) and adversarial model-response shapes must never crash
 * and must degrade gracefully.
 */
class ReviewGradleBuildParserTest {

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

    private ReviewGradleBuildTool tool(String modelText, int maxOutputChars) {
        return new ReviewGradleBuildTool(
                config(), new FakeOpusClient(modelText), new ReviewGradleBuildPromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String projectType, String gradleDsl, String reviewFocus,
            String buildFailureLogs) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review build");
        m.put("buildFilesContext", "plugins { id 'java' }");
        m.put("buildFailureLogs", buildFailureLogs);
        m.put("projectType", projectType);
        m.put("gradleDsl", gradleDsl);
        m.put("reviewFocus", reviewFocus);
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private ReviewGradleBuildOutput run(String modelText, String projectType, String gradleDsl,
            String reviewFocus) {
        return tool(modelText, 50_000).handle(args(projectType, gradleDsl, reviewFocus, ""));
    }

    @Test
    void groovyDslBuildReviewParses() {
        String text = "FINDINGS:\n- severity: HIGH\n  category: dependency_management\n  title: x\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "dependency_management");
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("HIGH");
    }

    @Test
    void kotlinDslBuildReviewParses() {
        String text = "REVIEW:\nThe build.gradle.kts uses tasks.register lazily.\n"
                + "PLUGIN_ISSUES:\n- plugin applied eagerly\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "kotlin", "plugin_configuration");
        assertThat(out.pluginIssues()).containsExactly("plugin applied eagerly");
    }

    @Test
    void settingsMultiModuleReviewParses() {
        String text = "MULTI_MODULE_ISSUES:\n- includeBuild not used\n- module names inconsistent\n";
        ReviewGradleBuildOutput out = run(text, "multi_module_platform", "groovy",
                "multi_module_governance");
        assertThat(out.multiModuleIssues()).hasSize(2);
    }

    @Test
    void versionCatalogReviewParses() {
        String text = "DEPENDENCY_ISSUES:\n- versions not centralized in libs.versions.toml\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "dependency_management");
        assertThat(out.dependencyIssues()).hasSize(1);
    }

    @Test
    void gradlePropertiesReviewParses() {
        String text = "PERFORMANCE_ISSUES:\n- parallel and caching not enabled\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "performance");
        assertThat(out.performanceIssues()).hasSize(1);
    }

    @Test
    void buildSrcConventionPluginReviewParses() {
        String text = "TASK_GRAPH_ISSUES:\n- afterEvaluate used in convention plugin\n";
        ReviewGradleBuildOutput out = run(text, "multi_module_platform", "kotlin", "task_graph");
        assertThat(out.taskGraphIssues()).hasSize(1);
    }

    @Test
    void springBootStarterBuildReviewParses() {
        String text = "DEPENDENCY_ISSUES:\n- starter pulls transitive test deps into runtime\n";
        ReviewGradleBuildOutput out = run(text, "spring_boot_starter", "groovy", "all");
        assertThat(out.dependencyIssues()).hasSize(1);
    }

    @Test
    void gradlePluginProjectReviewParses() {
        String text = "PUBLISHING_ISSUES:\n- plugin marker artifact missing\n";
        ReviewGradleBuildOutput out = run(text, "gradle_plugin", "kotlin", "publishing");
        assertThat(out.publishingIssues()).hasSize(1);
    }

    @Test
    void configurationCacheReviewParses() {
        String text = "CONFIGURATION_CACHE_ISSUES:\n- Project accessed at execution time\n"
                + "- Task uses Project.exec\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "configuration_cache");
        assertThat(out.configurationCacheIssues()).hasSize(2);
    }

    @Test
    void malformedResponseDoesNotCrashAndFallsBackToReview() {
        String text = "###garbage @@@ no sections here at all 123";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "all");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("garbage");
    }

    @Test
    void duplicatedSectionHeadingsDoNotCrash() {
        String text = "DEPENDENCY_ISSUES:\n- first\nDEPENDENCY_ISSUES:\n- second\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "all");
        assertThat(out.dependencyIssues()).contains("second");
    }

    @Test
    void missingFindingsSectionYieldsEmptyList() {
        String text = "SUMMARY:\nok\nREVIEW:\nbody\nDEPENDENCY_ISSUES:\n- d1\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "all");
        assertThat(out.findings()).isEmpty();
        assertThat(out.dependencyIssues()).containsExactly("d1");
    }

    @Test
    void missingConfigurationCacheIssuesSectionYieldsEmptyList() {
        String text = "REVIEW:\nbody\nFINDINGS:\n- severity: LOW\n  title: minor\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "all");
        assertThat(out.configurationCacheIssues()).isEmpty();
        assertThat(out.findings()).hasSize(1);
    }

    @Test
    void longResponseGetsTruncated() {
        StringBuilder sb = new StringBuilder("REVIEW:\n");
        for (int i = 0; i < 5000; i++) {
            sb.append("word").append(i).append(' ');
        }
        ReviewGradleBuildOutput out = tool(sb.toString(), 200)
                .handle(args("java_library", "groovy", "all", ""));
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void markdownTableInFindingsDoesNotCrash() {
        String text = "FINDINGS:\n| severity | title |\n| --- | --- |\n| HIGH | bad dep |\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "all");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void codeBlockInSuggestedChangesDoesNotCrash() {
        String text = "SUGGESTED_CHANGES:\n- use libs.guava\n```groovy\ndependencies { }\n```\n";
        ReviewGradleBuildOutput out = run(text, "java_library", "groovy", "all");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.suggestedChanges()).contains("use libs.guava");
    }

    @Test
    void buildFailureLogsInContextAreReviewedWithoutCrashing() {
        String text = "REVIEW:\nThe failure log shows an unresolved dependency.\n"
                + "FINDINGS:\n- severity: HIGH\n  category: dependency_management\n  title: unresolved\n";
        ReviewGradleBuildOutput out = tool(text, 50_000).handle(
                args("java_library", "groovy", "all",
                        "Could not resolve com.example:lib:1.0 > FAILURE"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.findings()).hasSize(1);
    }
}
