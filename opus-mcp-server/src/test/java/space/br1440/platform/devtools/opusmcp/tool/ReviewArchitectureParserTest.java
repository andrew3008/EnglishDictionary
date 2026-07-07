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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewArchitecturePromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewArchitectureOutput;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code review_architecture_with_opus}: representative architecture
 * review shapes and adversarial model-response shapes must never crash and must degrade gracefully.
 */
class ReviewArchitectureParserTest {

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

    private ReviewArchitectureTool tool(String modelText, int maxOutputChars) {
        return new ReviewArchitectureTool(
                config(), new FakeOpusClient(modelText), new ReviewArchitecturePromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String architectureStyle, String outputFormat) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review the proposal");
        m.put("architectureProposal", "A representative architecture proposal.");
        m.put("context", "A representative context.");
        m.put("reviewFocus", "all");
        m.put("architectureScope", "module");
        m.put("architectureStyle", architectureStyle);
        m.put("compatibilityMode", "preserve_api");
        m.put("riskLevel", "medium");
        m.put("outputFormat", outputFormat);
        return m;
    }

    private ReviewArchitectureOutput run(String modelText, String style, String outputFormat) {
        return tool(modelText, 20_000).handle(args(style, outputFormat));
    }

    @Test
    void adrReviewIsParsed() {
        String text = "SUMMARY:\nADR is sound.\n\nVERDICT:\nAPPROVE\n\n"
                + "REVIEW:\nDecision is well-justified.\n\n"
                + "FINDINGS:\n- severity: LOW\n  category: documentation\n  title: Add context section\n";
        ReviewArchitectureOutput out = run(text, "layered", "adr_review");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.verdict()).isEqualTo("APPROVE");
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).category()).isEqualTo("documentation");
    }

    @Test
    void migrationPlanReviewIsParsed() {
        String text = "FINDINGS:\n- severity: BLOCKER\n  category: migration\n  title: No rollback\n"
                + "  details: plan lacks rollback\n  recommendation: add rollback step\n\n"
                + "ROLLBACK_NOTES:\n- define a reversible migration\n";
        ReviewArchitectureOutput out = run(text, "layered", "decision_memo");
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("BLOCKER");
        assertThat(out.rollbackNotes()).hasSize(1);
    }

    @Test
    void springBootStarterReviewIsParsed() {
        String text = "REVIEW:\nFollows starter conventions.\n\n"
                + "FINDINGS:\n- severity: MEDIUM\n  category: api_compatibility\n"
                + "  title: Auto-config ordering\n  details: ensure ordering\n";
        ReviewArchitectureOutput out = run(text, "spring_boot_starter", "structured_review");
        assertThat(out.findings()).hasSize(1);
        assertThat(out.review()).contains("starter conventions");
    }

    @Test
    void observabilityReviewIsParsed() {
        String text = "OBSERVABILITY_CHECKS:\n- verify spans\n- verify metrics\n\n"
                + "FINDINGS:\n- severity: INFO\n  category: observability\n  title: Add trace ids\n";
        ReviewArchitectureOutput out = run(text, "observability_pipeline", "checklist");
        assertThat(out.observabilityChecks()).hasSize(2);
        assertThat(out.findings().get(0).category()).isEqualTo("observability");
    }

    @Test
    void multiModuleReviewIsParsed() {
        String text = "RISK_MATRIX:\n- risk: classpath conflict\n  likelihood: MEDIUM\n  impact: HIGH\n"
                + "  mitigation: use a BOM\n";
        ReviewArchitectureOutput out = run(text, "layered", "risk_matrix");
        assertThat(out.riskMatrix()).hasSize(1);
        assertThat(out.riskMatrix().get(0).impact()).isEqualTo("HIGH");
    }

    @Test
    void securityFocusedReviewIsParsed() {
        String text = "FINDINGS:\n- severity: HIGH\n  category: security\n  title: Secret in config\n"
                + "  details: secret stored in plaintext\n  recommendation: use a vault\n";
        ReviewArchitectureOutput out = run(text, "hexagonal", "structured_review");
        assertThat(out.findings().get(0).category()).isEqualTo("security");
    }

    @Test
    void performanceOperabilityReviewIsParsed() {
        String text = "FINDINGS:\n- severity: MEDIUM\n  category: performance\n  title: N+1 calls\n"
                + "- severity: LOW\n  category: operability\n  title: Missing health check\n";
        ReviewArchitectureOutput out = run(text, "event_driven", "structured_review");
        assertThat(out.findings()).hasSize(2);
        assertThat(out.findings().get(1).category()).isEqualTo("operability");
    }

    @Test
    void malformedModelResponseDoesNotCrash() {
        ReviewArchitectureOutput out = run(":::::\n\n``` unterminated\n- \n", "unknown", "structured_review");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out).isNotNull();
    }

    @Test
    void duplicatedSectionHeadingsAreMerged() {
        String text = "FINDINGS:\n- severity: HIGH\n  title: A\n\n"
                + "FINDINGS:\n- severity: LOW\n  title: B\n";
        ReviewArchitectureOutput out = run(text, "layered", "structured_review");
        assertThat(out.findings()).hasSize(2);
    }

    @Test
    void missingFindingsSectionYieldsEmptyList() {
        String text = "REVIEW:\nLooks fine overall.\n\nRISK_MATRIX:\n- risk: r\n  likelihood: LOW\n  impact: LOW\n";
        ReviewArchitectureOutput out = run(text, "layered", "structured_review");
        assertThat(out.findings()).isEmpty();
        assertThat(out.riskMatrix()).hasSize(1);
    }

    @Test
    void emptyRiskMatrixSectionYieldsEmptyList() {
        String text = "REVIEW:\nNo material risks.\n\nRISK_MATRIX:\n\nTESTS_TO_ADD:\n- add a test\n";
        ReviewArchitectureOutput out = run(text, "unknown", "structured_review");
        assertThat(out.riskMatrix()).isEmpty();
        assertThat(out.testsToAdd()).hasSize(1);
    }

    @Test
    void markdownTableRiskMatrixIsParsed() {
        String text = "RISK_MATRIX:\n"
                + "| risk | likelihood | impact | mitigation |\n"
                + "| --- | --- | --- | --- |\n"
                + "| Data loss | HIGH | HIGH | backups |\n"
                + "| Latency | LOW | MEDIUM | caching |\n";
        ReviewArchitectureOutput out = run(text, "layered", "risk_matrix");
        assertThat(out.riskMatrix()).hasSize(2);
        assertThat(out.riskMatrix().get(0).risk()).isEqualTo("Data loss");
        assertThat(out.riskMatrix().get(1).mitigation()).isEqualTo("caching");
    }

    @Test
    void longResponseIsTruncated() {
        StringBuilder sb = new StringBuilder("REVIEW:\n");
        for (int i = 0; i < 5_000; i++) {
            sb.append("line ").append(i).append('\n');
        }
        ReviewArchitectureOutput out = tool(sb.toString(), 2_000).handle(args("layered", "structured_review"));
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void codeBlockInReviewIsPreserved() {
        String text = "REVIEW:\n```java\nclass Foo {}\n```\n\n"
                + "FINDINGS:\n- severity: LOW\n  title: demo\n";
        ReviewArchitectureOutput out = run(text, "layered", "structured_review");
        assertThat(out.review()).contains("class Foo");
        assertThat(out.findings()).hasSize(1);
    }
}
