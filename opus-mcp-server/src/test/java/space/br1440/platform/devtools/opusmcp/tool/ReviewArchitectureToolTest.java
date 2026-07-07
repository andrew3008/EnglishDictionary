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
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureRisk;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewArchitectureOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewArchitectureToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 41, 23);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ReviewArchitectureTool tool(OpusClient client) {
        return new ReviewArchitectureTool(
                config(), client, new ReviewArchitecturePromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review the proposed auto-configuration split for the tracing starter");
        m.put("architectureProposal", "Split the starter into core and autoconfigure modules; "
                + "expose TracingProperties via @ConfigurationProperties.");
        m.put("context", "Existing single-module starter, Spring Boot 3.x, Actuator enabled");
        m.put("constraints", "Preserve the public bean API");
        m.put("reviewFocus", "api_compatibility");
        m.put("architectureScope", "multi_module");
        m.put("architectureStyle", "spring_boot_starter");
        m.put("compatibilityMode", "preserve_api");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            A reasonable starter split that mostly preserves the public API.

            VERDICT:
            APPROVE_WITH_CHANGES

            REVIEW:
            The core/autoconfigure split follows Spring Boot conventions. Watch bean ordering.

            FINDINGS:
            - severity: HIGH
              category: api_compatibility
              title: Bean name change risk
              details: Moving beans across modules may rename auto-config classes
              recommendation: Keep fully-qualified bean names stable
            - severity: MEDIUM
              category: observability
              title: Actuator endpoint exposure
              details: Tracing endpoint must remain opt-in
              recommendation: Gate behind a property

            RISK_MATRIX:
            - risk: Breaking auto-config ordering
              likelihood: MEDIUM
              impact: HIGH
              mitigation: Add @AutoConfigureBefore/After and tests

            TRADE_OFFS:
            - Smaller core jar vs more modules to maintain

            ALTERNATIVES:
            - Keep single module and use conditional beans

            OPEN_QUESTIONS:
            - Which Spring Boot versions must be supported?

            TESTS_TO_ADD:
            - AutoConfigurationImportSelector contract test

            OBSERVABILITY_CHECKS:
            - Verify trace spans still exported

            ROLLOUT_NOTES:
            - Release as a minor version

            ROLLBACK_NOTES:
            - Revert to single-module artifact

            RISKS:
            - Downstream classpath conflicts

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - Consumers use the BOM
            """;

    @Test
    void okResponseParsesAllSections() {
        ReviewArchitectureOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("starter split");
        assertThat(out.verdict()).isEqualTo("APPROVE_WITH_CHANGES");
        assertThat(out.review()).contains("Spring Boot conventions");

        assertThat(out.findings()).hasSize(2);
        ArchitectureFinding first = out.findings().get(0);
        assertThat(first.severity()).isEqualTo("HIGH");
        assertThat(first.category()).isEqualTo("api_compatibility");
        assertThat(first.title()).isEqualTo("Bean name change risk");
        assertThat(first.details()).contains("rename auto-config");
        assertThat(first.recommendation()).contains("stable");

        assertThat(out.riskMatrix()).hasSize(1);
        ArchitectureRisk risk = out.riskMatrix().get(0);
        assertThat(risk.risk()).isEqualTo("Breaking auto-config ordering");
        assertThat(risk.likelihood()).isEqualTo("MEDIUM");
        assertThat(risk.impact()).isEqualTo("HIGH");
        assertThat(risk.mitigation()).contains("@AutoConfigureBefore");

        assertThat(out.tradeOffs()).hasSize(1);
        assertThat(out.alternatives()).hasSize(1);
        assertThat(out.openQuestions()).hasSize(1);
        assertThat(out.testsToAdd()).containsExactly("AutoConfigurationImportSelector contract test");
        assertThat(out.observabilityChecks()).containsExactly("Verify trace spans still exported");
        assertThat(out.rolloutNotes()).containsExactly("Release as a minor version");
        assertThat(out.rollbackNotes()).containsExactly("Revert to single-module artifact");
        assertThat(out.risks()).containsExactly("Downstream classpath conflicts");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("Consumers use the BOM");
        assertThat(out.inputTokenEstimate()).isEqualTo(41);
        assertThat(out.outputTokenEstimate()).isEqualTo(23);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void findingWithUnknownSeverityFallsBackToMedium() {
        String text = "FINDINGS:\n- severity: catastrophic\n  category: nonsense\n  title: Foo\n";
        ReviewArchitectureOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo("MEDIUM");
        assertThat(out.findings().get(0).category()).isEqualTo("other");
    }

    @Test
    void riskMatrixMarkdownTableIsParsed() {
        String text = """
                RISK_MATRIX:
                | risk | likelihood | impact | mitigation |
                | --- | --- | --- | --- |
                | Config drift | HIGH | MEDIUM | Add validation |
                """;
        ReviewArchitectureOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.riskMatrix()).hasSize(1);
        ArchitectureRisk r = out.riskMatrix().get(0);
        assertThat(r.risk()).isEqualTo("Config drift");
        assertThat(r.likelihood()).isEqualTo("HIGH");
        assertThat(r.impact()).isEqualTo("MEDIUM");
        assertThat(r.mitigation()).isEqualTo("Add validation");
    }

    @Test
    void unknownVerdictFallsBackToNeedsMoreContext() {
        String text = "VERDICT:\nMAYBE_LATER\nREVIEW:\nsomething\n";
        ReviewArchitectureOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.verdict()).isEqualTo("NEEDS_MORE_CONTEXT");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInReview() {
        String text = "Here is a freeform review with no recognizable sections at all.";
        ReviewArchitectureOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.review()).contains("freeform review");
        assertThat(out.findings()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review");
        m.put("architectureProposal", ""); // blank proposal
        m.put("reviewFocus", "all");
        m.put("architectureScope", "module");
        m.put("architectureStyle", "layered");
        m.put("compatibilityMode", "unknown");
        m.put("riskLevel", "low");
        m.put("outputFormat", "structured_review");
        ReviewArchitectureOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
        assertThat(out.summary()).contains("Invalid or insufficient input");
        assertThat(out.verdict()).isEqualTo("NEEDS_MORE_CONTEXT");
    }

    @Test
    void successfulResponseParsesRequestChangesVerdict() {
        String text = "SUMMARY:\nNeeds API hardening.\n\nVERDICT:\nREQUEST_CHANGES\n\nREVIEW:\nSplit modules.\n";
        ReviewArchitectureOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.verdict()).isEqualTo("REQUEST_CHANGES");
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("compatibilityMode", "not-a-mode");
        ReviewArchitectureOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void proposalIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("REVIEW:\nok\n");
        tool(client).handle(args());
        assertThat(client.last.get().userPrompt()).contains("core and autoconfigure modules");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("do not apply patches");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
    }
}
