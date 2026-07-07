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
import space.br1440.platform.devtools.opusmcp.prompt.WriteMdxDocPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.WriteMdxDocOutput;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code write_mdx_doc_with_opus}: representative MDX response shapes
 * and adversarial model-response shapes must never crash and must degrade gracefully.
 */
class WriteMdxDocParserTest {

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

    private WriteMdxDocTool tool(String modelText, int maxOutputChars) {
        return new WriteMdxDocTool(
                config(), new FakeOpusClient(modelText), new WriteMdxDocPromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String docType, String outputFormat) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Draft documentation");
        m.put("docSubject", "Tracing Starter");
        m.put("targetAudience", "mixed");
        m.put("libraryContext", "A representative library context.");
        m.put("docType", docType);
        m.put("outputFormat", outputFormat);
        m.put("riskLevel", "medium");
        return m;
    }

    private WriteMdxDocOutput run(String modelText, String docType, String outputFormat) {
        return tool(modelText, 20_000).handle(args(docType, outputFormat));
    }

    @Test
    void fullMdxPageWithFrontMatterParses() {
        String text = "SUMMARY:\nFull page.\n\nFRONT_MATTER:\n---\nid: x\ntitle: X\n---\n\n"
                + "IMPORTS:\n- import A from './A';\n\nMDX_CONTENT:\n# Title\n\nbody\n\n"
                + "OUTLINE:\n- One\n- Two\n";
        WriteMdxDocOutput out = run(text, "library_guide", "mdx_page");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.frontMatter()).contains("id: x");
        assertThat(out.imports()).containsExactly("import A from './A';");
        assertThat(out.mdxContent()).contains("# Title");
        assertThat(out.outline()).containsExactly("One", "Two");
    }

    @Test
    void mdxSectionOnlyParses() {
        String text = "MDX_CONTENT:\n## Configuration\n\nSet the property.\n";
        WriteMdxDocOutput out = run(text, "reference", "mdx_section");
        assertThat(out.mdxContent()).contains("## Configuration");
        assertThat(out.frontMatter()).isEmpty();
    }

    @Test
    void outlineOnlyResponseParses() {
        String text = "OUTLINE:\n- Intro\n- Setup\n- Usage\n";
        WriteMdxDocOutput out = run(text, "how_to", "outline");
        assertThat(out.outline()).containsExactly("Intro", "Setup", "Usage");
    }

    @Test
    void importsWithJsxComponentsParse() {
        String text = "IMPORTS:\n- import Tabs from '@theme/Tabs';\n- import TabItem from '@theme/TabItem';\n"
                + "MDX_CONTENT:\nbody\n";
        WriteMdxDocOutput out = run(text, "library_guide", "mdx_page");
        assertThat(out.imports()).hasSize(2);
        assertThat(out.imports().get(0)).contains("@theme/Tabs");
    }

    @Test
    void admonitionsParse() {
        String text = "ADMONITIONS:\n- tip: do this\n- warning: avoid that\n";
        WriteMdxDocOutput out = run(text, "how_to", "reviewable_draft");
        assertThat(out.admonitions()).containsExactly("tip: do this", "warning: avoid that");
    }

    @Test
    void codeFencesInsideMdxContentAreKept() {
        String text = "MDX_CONTENT:\nIntro\n\n```java\nclass A {}\n```\n\nOUTLINE:\n- only outline header inside fence?\n";
        WriteMdxDocOutput out = run(text, "library_guide", "mdx_page");
        assertThat(out.mdxContent()).contains("class A {}");
    }

    @Test
    void jsxComponentsInsideMdxContentAreKept() {
        String text = "MDX_CONTENT:\n<Tabs>\n  <TabItem value=\"a\">A</TabItem>\n</Tabs>\n";
        WriteMdxDocOutput out = run(text, "library_guide", "mdx_page");
        assertThat(out.mdxContent()).contains("<TabItem value=\"a\">A</TabItem>");
    }

    @Test
    void markdownTableInsideMdxContentIsKept() {
        String text = "MDX_CONTENT:\n| key | value |\n| --- | --- |\n| a | b |\n";
        WriteMdxDocOutput out = run(text, "reference", "mdx_section");
        assertThat(out.mdxContent()).contains("| key | value |");
    }

    @Test
    void malformedResponseDoesNotCrash() {
        String text = ":::::\n###\n- \n```\n";
        WriteMdxDocOutput out = run(text, "unknown", "reviewable_draft");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).isNotBlank();
    }

    @Test
    void duplicatedSectionHeadingsAreMerged() {
        String text = "OUTLINE:\n- One\n\nOUTLINE:\n- Two\n";
        WriteMdxDocOutput out = run(text, "how_to", "outline");
        assertThat(out.outline()).contains("Two");
    }

    @Test
    void missingFrontMatterSectionIsEmpty() {
        String text = "MDX_CONTENT:\n# No front matter here\n";
        WriteMdxDocOutput out = run(text, "how_to", "mdx_section");
        assertThat(out.frontMatter()).isEmpty();
    }

    @Test
    void missingImportsSectionIsEmptyList() {
        String text = "MDX_CONTENT:\n# No imports\n";
        WriteMdxDocOutput out = run(text, "how_to", "mdx_section");
        assertThat(out.imports()).isEmpty();
    }

    @Test
    void longResponseIsTruncatedWithoutCrash() {
        StringBuilder sb = new StringBuilder("MDX_CONTENT:\n");
        for (int i = 0; i < 5_000; i++) {
            sb.append("line ").append(i).append('\n');
        }
        WriteMdxDocOutput out = tool(sb.toString(), 200).handle(args("library_guide", "mdx_page"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void nonCompliantResponseFallsBackToMdxContent() {
        String text = "Totally freeform doc text without any section headers.";
        WriteMdxDocOutput out = run(text, "unknown", "reviewable_draft");
        assertThat(out.mdxContent()).contains("freeform doc text");
    }
}
