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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-robustness coverage for {@code design_class_hierarchy_with_opus}: representative hierarchy
 * shapes and adversarial model-response shapes must never crash and must degrade gracefully.
 */
class DesignClassHierarchyParserTest {

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

    private DesignClassHierarchyTool tool(String modelText, int maxOutputChars) {
        return new DesignClassHierarchyTool(
                config(), new FakeOpusClient(modelText), new DesignClassHierarchyPromptBuilder(),
                new SecretScanner(), new DenyList(), new LimitsGuard(50_000, 5_000, maxOutputChars),
                new ModelRegistry(), new ErrorMapper(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()), new AuditLogger());
    }

    private Map<String, Object> args(String architectureStyle) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Design a hierarchy");
        m.put("language", "java");
        m.put("domainContext", "A representative domain context.");
        m.put("designGoal", "extensibility");
        m.put("scope", "module");
        m.put("architectureStyle", architectureStyle);
        m.put("riskLevel", "medium");
        m.put("outputFormat", "design_proposal");
        return m;
    }

    private DesignClassHierarchyOutput run(String modelText, String architectureStyle) {
        return tool(modelText, 20_000).handle(args(architectureStyle));
    }

    @Test
    void interfaceHeavyHierarchyIsParsed() {
        String text = "DESIGN_OVERVIEW:\nInterface-driven design.\n\n"
                + "PROPOSED_TYPES:\n"
                + "- name: Reader\n  kind: interface\n  responsibility: read\n"
                + "- name: Writer\n  kind: interface\n  responsibility: write\n"
                + "- name: Codec\n  kind: interface\n  responsibility: both\n";
        DesignClassHierarchyOutput out = run(text, "hexagonal");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.proposedTypes()).hasSize(3);
        assertThat(out.proposedTypes()).allMatch(t -> t.kind().equals("interface"));
    }

    @Test
    void abstractClassAndImplementationHierarchyIsParsed() {
        String text = "PROPOSED_TYPES:\n"
                + "- name: AbstractHandler\n  kind: abstract_class\n  responsibility: base\n"
                + "- name: ConcreteHandler\n  kind: class\n  responsibility: impl\n\n"
                + "RELATIONSHIPS:\n- from: ConcreteHandler\n  to: AbstractHandler\n  type: extends\n  reason: base\n";
        DesignClassHierarchyOutput out = run(text, "layered");
        assertThat(out.proposedTypes()).hasSize(2);
        assertThat(out.proposedTypes().get(0).kind()).isEqualTo("abstract_class");
        assertThat(out.relationships()).hasSize(1);
        assertThat(out.relationships().get(0).type()).isEqualTo("extends");
    }

    @Test
    void springBootStarterHierarchyIsParsed() {
        String text = "PROPOSED_TYPES:\n"
                + "- name: FooAutoConfiguration\n  kind: class\n  package: space.example.foo\n"
                + "  responsibility: auto config\n"
                + "- name: FooProperties\n  kind: class\n  responsibility: bind props\n"
                + "- name: FooClient\n  kind: interface\n  responsibility: client api\n";
        DesignClassHierarchyOutput out = run(text, "spring_boot_starter");
        assertThat(out.proposedTypes()).hasSize(3);
        assertThat(out.proposedTypes().get(0).name()).isEqualTo("FooAutoConfiguration");
    }

    @Test
    void interceptorPipelineHierarchyIsParsed() {
        String text = "PROPOSED_TYPES:\n"
                + "- name: Interceptor\n  kind: interface\n  responsibility: intercept\n"
                + "- name: InterceptorChain\n  kind: class\n  responsibility: orchestrate\n\n"
                + "RELATIONSHIPS:\n- InterceptorChain --composes--> Interceptor : holds chain\n";
        DesignClassHierarchyOutput out = run(text, "interceptor_pipeline");
        assertThat(out.proposedTypes()).hasSize(2);
        assertThat(out.relationships()).hasSize(1);
        assertThat(out.relationships().get(0).type()).isEqualTo("composes");
    }

    @Test
    void pluginStrategyHierarchyIsParsed() {
        String text = "PROPOSED_TYPES:\n"
                + "- name: Strategy\n  kind: interface\n  responsibility: behavior\n"
                + "- name: PluginRegistry\n  kind: class\n  responsibility: register strategies\n\n"
                + "RELATIONSHIPS:\n- from: PluginRegistry\n  to: Strategy\n  type: uses\n  reason: lookup\n";
        DesignClassHierarchyOutput out = run(text, "plugin");
        assertThat(out.proposedTypes()).hasSize(2);
        assertThat(out.relationships().get(0).type()).isEqualTo("uses");
    }

    @Test
    void domainModelHierarchyIsParsed() {
        String text = "PROPOSED_TYPES:\n"
                + "- name: Order\n  kind: record\n  responsibility: aggregate root\n"
                + "- name: OrderLine\n  kind: record\n  responsibility: line item\n"
                + "- name: OrderStatus\n  kind: enum\n  responsibility: lifecycle\n";
        DesignClassHierarchyOutput out = run(text, "domain_model");
        assertThat(out.proposedTypes()).hasSize(3);
        assertThat(out.proposedTypes().get(2).kind()).isEqualTo("enum");
    }

    @Test
    void malformedModelResponseDoesNotCrash() {
        DesignClassHierarchyOutput out = run(":::::\n\n``` unterminated\n- \n", "unknown");
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out).isNotNull();
    }

    @Test
    void duplicatedSectionHeadingsAreMerged() {
        String text = "PROPOSED_TYPES:\n- name: A\n  kind: class\n\n"
                + "PROPOSED_TYPES:\n- name: B\n  kind: class\n";
        DesignClassHierarchyOutput out = run(text, "layered");
        assertThat(out.proposedTypes()).hasSize(2);
    }

    @Test
    void missingRelationshipsSectionYieldsEmptyList() {
        String text = "PROPOSED_TYPES:\n- name: A\n  kind: class\n  responsibility: x\n";
        DesignClassHierarchyOutput out = run(text, "layered");
        assertThat(out.proposedTypes()).hasSize(1);
        assertThat(out.relationships()).isEmpty();
    }

    @Test
    void emptyProposedTypesSectionYieldsEmptyList() {
        String text = "DESIGN_OVERVIEW:\nNo concrete types yet.\n\nPROPOSED_TYPES:\n\n"
                + "IMPLEMENTATION_SLICES:\n- Investigate further\n";
        DesignClassHierarchyOutput out = run(text, "unknown");
        assertThat(out.proposedTypes()).isEmpty();
        assertThat(out.implementationSlices()).hasSize(1);
    }

    @Test
    void longResponseIsTruncated() {
        StringBuilder sb = new StringBuilder("DESIGN_OVERVIEW:\n");
        for (int i = 0; i < 5_000; i++) {
            sb.append("line ").append(i).append('\n');
        }
        DesignClassHierarchyOutput out = tool(sb.toString(), 2_000).handle(args("layered"));
        assertThat(out.truncated()).isTrue();
    }

    @Test
    void plantUmlBlockInDesignOverviewIsPreserved() {
        String text = "DESIGN_OVERVIEW:\n```plantuml\n@startuml\nclass Foo\n@enduml\n```\n\n"
                + "PROPOSED_TYPES:\n- name: Foo\n  kind: class\n  responsibility: demo\n";
        DesignClassHierarchyOutput out = run(text, "domain_model");
        assertThat(out.designOverview()).contains("@startuml");
        assertThat(out.proposedTypes()).hasSize(1);
    }
}
