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
import space.br1440.platform.devtools.opusmcp.tool.dto.MdxFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewMdxDocOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewMdxDocToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 47, 29);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ReviewMdxDocTool tool(OpusClient client) {
        return new ReviewMdxDocTool(
                config(), client, new ReviewMdxDocPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review the tracing starter guide for accuracy and MDX validity");
        m.put("mdxContent", "---\ntitle: Tracing\n---\n\n# Tracing\n\nSet platform.tracing.enabled=true.");
        m.put("docSubject", "Platform Tracing Starter");
        m.put("targetAudience", "application_developers");
        m.put("libraryContext", "Spring Boot starter that auto-configures tracing.");
        m.put("styleGuideContext", "Use second person, short sections.");
        m.put("mdxComponentsContext", "import Tabs from '@theme/Tabs'");
        m.put("constraints", "Keep it concise");
        m.put("reviewFocus", "accuracy");
        m.put("docType", "starter_guide");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            The starter guide is mostly accurate but has an unverified version claim.

            VERDICT:
            APPROVE_WITH_CHANGES

            REVIEW:
            The guide follows the style guide and uses valid MDX, but the version number is unverified.

            FINDINGS:
            - severity: HIGH
              category: claims
              title: Unverified version number
              details: The doc states version 1.0.0 which is not in the provided context
              recommendation: Confirm the actual published version before publishing
            - severity: LOW
              category: style
              title: Missing intro sentence
              details: The page jumps straight into config
              recommendation: Add a one-line intro

            MISSING_SECTIONS:
            - Prerequisites

            INCORRECT_OR_UNVERIFIED_CLAIMS:
            - Version 1.0.0 is not supported by the provided context

            MDX_ISSUES:
            - Tabs is imported but never used

            STYLE_ISSUES:
            - Inconsistent heading capitalization

            EXAMPLE_ISSUES:
            - The config example lacks a language tag on the fence

            SUGGESTED_EDITS:
            - Add a Prerequisites section before installation

            VALIDATION_CHECKLIST:
            - Run docusaurus build
            - Verify the version number

            RISKS:
            - Publishing an incorrect version could mislead users

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - Reader uses Spring Boot 3.x
            """;

    @Test
    void okResponseParsesAllSections() {
        ReviewMdxDocOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("unverified version");
        assertThat(out.verdict()).isEqualTo("APPROVE_WITH_CHANGES");
        assertThat(out.review()).contains("valid MDX");

        assertThat(out.findings()).hasSize(2);
        MdxFinding first = out.findings().get(0);
        assertThat(first.severity()).isEqualTo("HIGH");
        assertThat(first.category()).isEqualTo("claims");
        assertThat(first.title()).isEqualTo("Unverified version number");
        assertThat(first.details()).contains("1.0.0");
        assertThat(first.recommendation()).contains("Confirm");

        assertThat(out.missingSections()).containsExactly("Prerequisites");
        assertThat(out.incorrectOrUnverifiedClaims())
                .containsExactly("Version 1.0.0 is not supported by the provided context");
        assertThat(out.mdxIssues()).containsExactly("Tabs is imported but never used");
        assertThat(out.styleIssues()).containsExactly("Inconsistent heading capitalization");
        assertThat(out.exampleIssues()).containsExactly("The config example lacks a language tag on the fence");
        assertThat(out.suggestedEdits()).containsExactly("Add a Prerequisites section before installation");
        assertThat(out.validationChecklist()).containsExactly(
                "Run docusaurus build", "Verify the version number");
        assertThat(out.risks()).containsExactly("Publishing an incorrect version could mislead users");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("Reader uses Spring Boot 3.x");
        assertThat(out.inputTokenEstimate()).isEqualTo(47);
        assertThat(out.outputTokenEstimate()).isEqualTo(29);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void findingWithUnknownSeverityFallsBackToMedium() {
        String text = "FINDINGS:\n- severity: catastrophic\n  category: nonsense\n  title: Foo\n";
        ReviewMdxDocOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("MEDIUM");
        assertThat(out.findings().get(0).category()).isEqualTo("other");
    }

    @Test
    void unknownVerdictFallsBackToNeedsMoreContext() {
        String text = "VERDICT:\nMAYBE_LATER\nREVIEW:\nsomething\n";
        ReviewMdxDocOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.verdict()).isEqualTo("NEEDS_MORE_CONTEXT");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInReview() {
        String text = "Here is a freeform review with no recognizable sections at all.";
        ReviewMdxDocOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("freeform review");
        assertThat(out.findings()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review");
        m.put("mdxContent", ""); // blank content
        m.put("docSubject", "Subject");
        m.put("targetAudience", "mixed");
        m.put("reviewFocus", "all");
        m.put("docType", "how_to");
        m.put("riskLevel", "low");
        m.put("outputFormat", "structured_review");
        ReviewMdxDocOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("reviewFocus", "not-a-focus");
        ReviewMdxDocOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void mdxContentIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nok\n");
        tool(client).handle(args());
        assertThat(client.last.get().userPrompt()).contains("platform.tracing.enabled=true");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("recommend changes as text only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
    }
}
