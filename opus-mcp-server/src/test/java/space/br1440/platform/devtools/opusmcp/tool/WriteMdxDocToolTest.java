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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WriteMdxDocToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 55, 33);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private WriteMdxDocTool tool(OpusClient client) {
        return new WriteMdxDocTool(
                config(), client, new WriteMdxDocPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Draft a starter guide for the tracing starter");
        m.put("docSubject", "Platform Tracing Starter");
        m.put("targetAudience", "application_developers");
        m.put("libraryContext", "Spring Boot starter that auto-configures distributed tracing.");
        m.put("publicApi", "TracingProperties, TracingAutoConfiguration");
        m.put("configurationProperties", "platform.tracing.enabled=true");
        m.put("usageExamples", "Add the starter dependency and set platform.tracing.enabled=true");
        m.put("docStyleContext", "Use second person, short sections, code fences.");
        m.put("mdxComponentsContext", "import Tabs from '@theme/Tabs'");
        m.put("assetGuidelines", "Use SVG diagrams stored under static/img");
        m.put("constraints", "Keep it under 400 words");
        m.put("docType", "starter_guide");
        m.put("outputFormat", "mdx_page");
        m.put("riskLevel", "medium");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            A concise starter guide for enabling platform tracing.

            FRONT_MATTER:
            ---
            id: tracing-starter
            title: Platform Tracing Starter
            ---

            IMPORTS:
            - import Tabs from '@theme/Tabs';
            - import TabItem from '@theme/TabItem';

            MDX_CONTENT:
            # Platform Tracing Starter

            Add the dependency and enable tracing.

            ```properties
            platform.tracing.enabled=true
            ```

            <Tabs>
              <TabItem value="maven">maven</TabItem>
            </Tabs>

            OUTLINE:
            - Overview
            - Installation
            - Configuration

            EXAMPLES:
            - Maven dependency snippet

            ADMONITIONS:
            - tip: Enable sampling in production

            ASSETS_NEEDED:
            - Architecture diagram SVG

            LINKS_TO_ADD:
            - /docs/observability

            CLAIMS_TO_VERIFY:
            - Default sampling rate value

            VALIDATION_CHECKLIST:
            - Run docusaurus build
            - Verify imports resolve

            RISKS:
            - Property names may change

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - Reader uses Spring Boot 3.x
            """;

    @Test
    void okResponseParsesAllSections() {
        WriteMdxDocOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("starter guide");
        assertThat(out.frontMatter()).contains("id: tracing-starter");
        assertThat(out.imports()).contains("import Tabs from '@theme/Tabs';");
        assertThat(out.mdxContent()).contains("# Platform Tracing Starter");
        assertThat(out.mdxContent()).contains("platform.tracing.enabled=true");
        assertThat(out.mdxContent()).contains("<Tabs>");
        assertThat(out.outline()).containsExactly("Overview", "Installation", "Configuration");
        assertThat(out.examples()).containsExactly("Maven dependency snippet");
        assertThat(out.admonitions()).containsExactly("tip: Enable sampling in production");
        assertThat(out.assetsNeeded()).containsExactly("Architecture diagram SVG");
        assertThat(out.linksToAdd()).containsExactly("/docs/observability");
        assertThat(out.claimsToVerify()).containsExactly("Default sampling rate value");
        assertThat(out.validationChecklist()).containsExactly(
                "Run docusaurus build", "Verify imports resolve");
        assertThat(out.risks()).containsExactly("Property names may change");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("Reader uses Spring Boot 3.x");
        assertThat(out.inputTokenEstimate()).isEqualTo(55);
        assertThat(out.outputTokenEstimate()).isEqualTo(33);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void codeFenceContainingSectionWordsDoesNotSplit() {
        String text = """
                MDX_CONTENT:
                Intro text.

                ```text
                RISKS: this is inside a code fence and must not start a section
                ```

                More text.

                RISKS:
                - A real risk
                """;
        WriteMdxDocOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.mdxContent()).contains("inside a code fence");
        assertThat(out.risks()).containsExactly("A real risk");
    }

    @Test
    void nonCompliantResponseFallsBackToMdxContent() {
        String text = "Just a freeform paragraph with no recognizable sections.";
        WriteMdxDocOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.mdxContent()).contains("freeform paragraph");
        assertThat(out.outline()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Draft");
        m.put("docSubject", "");
        m.put("targetAudience", "mixed");
        m.put("libraryContext", "ctx");
        m.put("docType", "how_to");
        m.put("outputFormat", "mdx_section");
        m.put("riskLevel", "low");
        WriteMdxDocOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("docType", "not-a-type");
        WriteMdxDocOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void contextIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("MDX_CONTENT:\nok\n");
        tool(client).handle(args());
        assertThat(client.last.get().userPrompt()).contains("auto-configures distributed tracing");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("do not create files");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
    }
}
