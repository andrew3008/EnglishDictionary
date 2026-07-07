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
import space.br1440.platform.devtools.opusmcp.prompt.DesignClassHierarchyPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.DesignClassHierarchyOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ProposedType;
import space.br1440.platform.devtools.opusmcp.tool.dto.TypeRelationship;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DesignClassHierarchyToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            last.set(request);
            return new OpusResponse(text, 31, 17);
        }
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private DesignClassHierarchyTool tool(OpusClient client) {
        return new DesignClassHierarchyTool(
                config(), client, new DesignClassHierarchyPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Design a payment processing hierarchy");
        m.put("language", "java");
        m.put("domainContext", "Payments domain with multiple providers and retry semantics");
        m.put("existingTypes", "interface PaymentGateway { ... }");
        m.put("packageContext", "space.example.payments");
        m.put("constraints", "Keep API backwards compatible");
        m.put("designGoal", "extensibility");
        m.put("scope", "module");
        m.put("architectureStyle", "clean_architecture");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "design_proposal");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            A small extensible payment hierarchy with a gateway abstraction.

            DESIGN_OVERVIEW:
            Use a PaymentGateway interface with per-provider implementations and a coordinator.

            PROPOSED_TYPES:
            - name: PaymentGateway
              kind: interface
              package: space.example.payments
              responsibility: Abstract a payment provider
              publicApi: charge(Money), refund(TxnId)
              collaborators: PaymentResult, Money
              notes: Keep provider-agnostic
            - name: StripeGateway
              kind: class
              package: space.example.payments.stripe
              responsibility: Stripe implementation
              publicApi: charge(Money)
              collaborators: PaymentGateway
              notes: Wraps Stripe SDK

            RELATIONSHIPS:
            - from: StripeGateway
              to: PaymentGateway
              type: implements
              reason: Provider-specific implementation

            PACKAGE_PLAN:
            - space.example.payments (core)
            - space.example.payments.stripe (provider)

            IMPLEMENTATION_SLICES:
            - Define PaymentGateway interface
            - Implement StripeGateway

            EXTENSION_POINTS:
            - Add new provider by implementing PaymentGateway

            DESIGN_ALTERNATIVES:
            - Use abstract base class instead of interface

            TESTS_TO_ADD:
            - PaymentGatewayContractTest

            RISKS:
            - Provider API drift

            ANTI_PATTERNS_TO_AVOID:
            - God object coordinator

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - Money type already exists
            """;

    @Test
    void okResponseParsesAllSections() {
        DesignClassHierarchyOutput out = tool(new FakeOpusClient(STRUCTURED)).handle(args());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("extensible payment hierarchy");
        assertThat(out.designOverview()).contains("PaymentGateway interface");
        assertThat(out.proposedTypes()).hasSize(2);

        ProposedType first = out.proposedTypes().get(0);
        assertThat(first.name()).isEqualTo("PaymentGateway");
        assertThat(first.kind()).isEqualTo("interface");
        assertThat(first.packageName()).isEqualTo("space.example.payments");
        assertThat(first.responsibility()).contains("Abstract a payment provider");
        assertThat(first.publicApi()).containsExactly("charge(Money)", "refund(TxnId)");
        assertThat(first.collaborators()).containsExactly("PaymentResult", "Money");

        assertThat(out.relationships()).hasSize(1);
        TypeRelationship rel = out.relationships().get(0);
        assertThat(rel.from()).isEqualTo("StripeGateway");
        assertThat(rel.to()).isEqualTo("PaymentGateway");
        assertThat(rel.type()).isEqualTo("implements");

        assertThat(out.packagePlan()).hasSize(2);
        assertThat(out.implementationSlices()).hasSize(2);
        assertThat(out.extensionPoints()).hasSize(1);
        assertThat(out.designAlternatives()).hasSize(1);
        assertThat(out.testsToAdd()).containsExactly("PaymentGatewayContractTest");
        assertThat(out.risks()).containsExactly("Provider API drift");
        assertThat(out.antiPatternsToAvoid()).containsExactly("God object coordinator");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("Money type already exists");
        assertThat(out.inputTokenEstimate()).isEqualTo(31);
        assertThat(out.outputTokenEstimate()).isEqualTo(17);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void proposedTypeWithUnknownKindFallsBackToClass() {
        String text = "PROPOSED_TYPES:\n- name: Foo\n  kind: widget\n  responsibility: do things\n";
        DesignClassHierarchyOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.proposedTypes()).hasSize(1);
        assertThat(out.proposedTypes().get(0).kind()).isEqualTo("class");
    }

    @Test
    void relationshipArrowFormIsParsed() {
        String text = "RELATIONSHIPS:\n- StripeGateway --implements--> PaymentGateway : provider impl\n";
        DesignClassHierarchyOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.relationships()).hasSize(1);
        TypeRelationship rel = out.relationships().get(0);
        assertThat(rel.from()).isEqualTo("StripeGateway");
        assertThat(rel.to()).isEqualTo("PaymentGateway");
        assertThat(rel.type()).isEqualTo("implements");
        assertThat(rel.reason()).contains("provider impl");
    }

    @Test
    void unknownRelationshipTypeFallsBackToUses() {
        String text = "RELATIONSHIPS:\n- from: A\n  to: B\n  type: frobnicates\n  reason: x\n";
        DesignClassHierarchyOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.relationships()).hasSize(1);
        assertThat(out.relationships().get(0).type()).isEqualTo("uses");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInDesignOverview() {
        String text = "Here is a freeform design with no recognizable sections at all.";
        DesignClassHierarchyOutput out = tool(new FakeOpusClient(text)).handle(args());
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.designOverview()).contains("freeform design");
        assertThat(out.proposedTypes()).isEmpty();
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Design");
        m.put("domainContext", ""); // blank context
        m.put("language", "java");
        m.put("designGoal", "extensibility");
        m.put("scope", "module");
        m.put("architectureStyle", "layered");
        m.put("riskLevel", "low");
        m.put("outputFormat", "design_proposal");
        DesignClassHierarchyOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args();
        m.put("architectureStyle", "not-a-style");
        DesignClassHierarchyOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void domainContextIsForwardedToModelPromptAsData() {
        FakeOpusClient client = new FakeOpusClient("DESIGN_OVERVIEW:\nok\n");
        tool(client).handle(args());
        assertThat(client.last.get().userPrompt()).contains("Payments domain with multiple providers");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("do not apply patches");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("untrusted data");
    }
}
