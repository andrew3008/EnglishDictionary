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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewMdxDocPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewMdxDocOutput;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code review_mdx_doc_with_opus}: representative review response
 * shapes and adversarial model-response shapes must never crash and must degrade gracefully.
 */
class ReviewMdxDocParserTest {

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

    private ReviewMdxDocTool tool(String modelText, int maxOutputChars) {
        return new ReviewMdxDocTool(
                config(), new FakeOpusClient(modelText), new ReviewMdxDocPromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review documentation");
        m.put("mdxContent", "# Doc\n\nbody");
        m.put("docSubject", "Subject");
        m.put("targetAudience", "mixed");
        m.put("reviewFocus", "all");
        m.put("docType", "reference");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private ReviewMdxDocOutput run(String modelText) {
        return tool(modelText, 50_000).handle(args());
    }

    @Test
    void mdxPageReviewWithFrontMatterInExample() {
        String text = "SUMMARY:\nReview of a full page.\nVERDICT:\nAPPROVE\n"
                + "REVIEW:\nThe front matter block:\n```mdx\n---\ntitle: X\n---\n```\nlooks valid.\n"
                + "MDX_ISSUES:\n- none\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.verdict()).isEqualTo("APPROVE");
        assertThat(out.review()).contains("title: X");
    }

    @Test
    void importsAndJsxComponentReview() {
        String text = "MDX_ISSUES:\n- import Tabs from '@theme/Tabs' is unused\n"
                + "- <Tabs> opened but never closed\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.mdxIssues()).hasSize(2);
        assertThat(out.mdxIssues().get(0)).contains("@theme/Tabs");
    }

    @Test
    void codeFenceInsideReviewIsPreserved() {
        String text = "REVIEW:\nExample:\n```js\nconst x = 1;\n```\nend.\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.review()).contains("const x = 1;");
    }

    @Test
    void manyFindingsParse() {
        StringBuilder sb = new StringBuilder("FINDINGS:\n");
        for (int i = 0; i < 12; i++) {
            sb.append("- severity: LOW\n  category: style\n  title: Issue ").append(i).append("\n");
        }
        ReviewMdxDocOutput out = run(sb.toString());
        assertThat(out.findings()).hasSize(12);
    }

    @Test
    void malformedResponseDoesNotCrashAndFallsBackToReview() {
        String text = "###garbage @@@ no sections here at all 123";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("garbage");
    }

    @Test
    void duplicatedSectionHeadingsDoNotCrash() {
        String text = "MDX_ISSUES:\n- first\nMDX_ISSUES:\n- second\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.mdxIssues()).contains("second");
    }

    @Test
    void missingFindingsSectionYieldsEmptyFindings() {
        String text = "SUMMARY:\nok\nREVIEW:\nbody\nSTYLE_ISSUES:\n- tone\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.findings()).isEmpty();
        assertThat(out.styleIssues()).containsExactly("tone");
    }

    @Test
    void missingMdxIssuesSectionYieldsEmptyList() {
        String text = "SUMMARY:\nok\nREVIEW:\nbody\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.mdxIssues()).isEmpty();
    }

    @Test
    void longResponseGetsTruncated() {
        StringBuilder sb = new StringBuilder("REVIEW:\n");
        for (int i = 0; i < 5000; i++) {
            sb.append("word").append(i).append(' ');
        }
        ReviewMdxDocOutput out = tool(sb.toString(), 200).handle(args());
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void markdownTableInFindingsDoesNotCrash() {
        String text = "FINDINGS:\n| severity | category | title |\n| --- | --- | --- |\n"
                + "| HIGH | claims | bad claim |\n";
        ReviewMdxDocOutput out = run(text);
        // Table rows are not key:value findings; parser must not crash and produces a usable output.
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void codeBlockInSuggestedEditsIsSkippedNotCrashing() {
        String text = "SUGGESTED_EDITS:\n- Add a prereqs section\n```mdx\n## Prerequisites\n```\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.suggestedEdits()).contains("Add a prereqs section");
    }

    @Test
    void admonitionAndTableInReviewArePreserved() {
        String text = "REVIEW:\n:::note\nbe careful\n:::\n\n| a | b |\n| - | - |\n| 1 | 2 |\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.review()).contains(":::note");
        assertThat(out.review()).contains("| 1 | 2 |");
    }

    @Test
    void brokenJsxInContentReviewDoesNotCrash() {
        String text = "MDX_ISSUES:\n- <Foo prop={ broken syntax here\n";
        ReviewMdxDocOutput out = run(text);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.mdxIssues()).hasSize(1);
    }
}
