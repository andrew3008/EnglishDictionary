package space.br1440.platform.devtools.opusmcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.AnalyzeBuildFailureTool;
import space.br1440.platform.devtools.opusmcp.tool.DesignClassHierarchyTool;
import space.br1440.platform.devtools.opusmcp.tool.EchoMcpConnectionTool;
import space.br1440.platform.devtools.opusmcp.tool.ExplainDiffTool;
import space.br1440.platform.devtools.opusmcp.tool.GenerateCodeTool;
import space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool;
import space.br1440.platform.devtools.opusmcp.tool.RefactorPlanTool;
import space.br1440.platform.devtools.opusmcp.tool.ResearchWithPerplexityTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewArchitectureTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewCodeTool;
import space.br1440.platform.devtools.opusmcp.tool.GenerateMigrationPlanTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewGradleBuildTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewMdxDocTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewTestsTool;
import space.br1440.platform.devtools.opusmcp.tool.WriteMdxDocTool;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the MCP tool input schemas against accidental drift that would break Cursor integration.
 */
class ToolSchemaCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    private List<String> textValues(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(n -> values.add(n.asText()));
        }
        return values;
    }

    @Test
    void generateCodeSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(GenerateCodeTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void generateCodeRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(GenerateCodeTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "outputFormat", "riskLevel");
    }

    @Test
    void generateCodeOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(GenerateCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("context")).isTrue();
        assertThat(props.has("constraints")).isTrue();
    }

    @Test
    void generateCodeLanguageEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void generateCodeOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder(
                        "unified_diff", "full_file", "code_block", "implementation_plan", "review");
    }

    @Test
    void generateCodeRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void echoSchemaHasMessagePropertyAndNoAdditionalProperties() throws Exception {
        JsonNode schema = parse(EchoMcpConnectionTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("message")).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void echoDescriptionImpliesNoFileOrNetworkCapabilities() {
        String description = EchoMcpConnectionTool.DESCRIPTION.toLowerCase();
        assertThat(description).contains("does not read or write files");
        assertThat(description).contains("call any network");
    }

    @Test
    void toolNamesAreStable() {
        assertThat(EchoMcpConnectionTool.TOOL_NAME).isEqualTo("echo_mcp_connection");
        assertThat(GenerateCodeTool.TOOL_NAME).isEqualTo("generate_code_with_opus");
        assertThat(ReviewCodeTool.TOOL_NAME).isEqualTo("review_code_with_opus");
        assertThat(GenerateTestsTool.TOOL_NAME).isEqualTo("generate_tests_with_opus");
        assertThat(RefactorPlanTool.TOOL_NAME).isEqualTo("refactor_plan_with_opus");
        assertThat(ExplainDiffTool.TOOL_NAME).isEqualTo("explain_diff_with_opus");
        assertThat(ResearchWithPerplexityTool.TOOL_NAME).isEqualTo("research_with_perplexity");
        assertThat(AnalyzeBuildFailureTool.TOOL_NAME).isEqualTo("analyze_build_failure_with_opus");
        assertThat(DesignClassHierarchyTool.TOOL_NAME).isEqualTo("design_class_hierarchy_with_opus");
        assertThat(ReviewArchitectureTool.TOOL_NAME).isEqualTo("review_architecture_with_opus");
        assertThat(WriteMdxDocTool.TOOL_NAME).isEqualTo("write_mdx_doc_with_opus");
        assertThat(EchoMcpConnectionTool.SERVER_NAME).isEqualTo("java-mcp-opus-server");
    }

    @Test
    void reviewCodeSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(ReviewCodeTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void reviewCodeRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(ReviewCodeTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "code", "reviewFocus", "riskLevel",
                        "outputFormat");
    }

    @Test
    void reviewCodeOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(ReviewCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("context")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("code")).isTrue();
    }

    @Test
    void reviewCodeReviewFocusEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("reviewFocus").path("enum")))
                .containsExactlyInAnyOrder("correctness", "security", "performance",
                        "maintainability", "tests", "architecture", "all");
    }

    @Test
    void reviewCodeOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("structured_review", "markdown", "checklist");
    }

    @Test
    void reviewCodeLanguageEnumMatchesGenerate() throws Exception {
        JsonNode props = parse(ReviewCodeTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void reviewDescriptionStatesReadOnly() {
        String d = ReviewCodeTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("apply patches");
    }

    @Test
    void generateTestsSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(GenerateTestsTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void generateTestsRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(GenerateTestsTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "code", "testFramework", "testType",
                        "coverageFocus", "riskLevel", "outputFormat");
    }

    @Test
    void generateTestsOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(GenerateTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("context")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("code")).isTrue();
    }

    @Test
    void generateTestsLanguageEnumMatchesGenerate() throws Exception {
        JsonNode props = parse(GenerateTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void generateTestsFrameworkEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("testFramework").path("enum")))
                .containsExactlyInAnyOrder("junit5", "testng", "mockito", "assertj",
                        "spring_boot_test", "kotest", "go_test", "other");
    }

    @Test
    void generateTestsTypeEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("testType").path("enum")))
                .containsExactlyInAnyOrder("unit", "integration", "contract", "slice", "property",
                        "regression", "all");
    }

    @Test
    void generateTestsCoverageFocusEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("coverageFocus").path("enum")))
                .containsExactlyInAnyOrder("happy_path", "edge_cases", "error_handling",
                        "concurrency", "security", "performance", "serialization", "all");
    }

    @Test
    void generateTestsOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("test_code", "test_plan", "checklist", "structured_tests");
    }

    @Test
    void generateTestsRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void generateTestsDescriptionStatesReadOnly() {
        String d = GenerateTestsTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run tests");
        assertThat(d).contains("apply patches");
    }

    @Test
    void refactorPlanSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(RefactorPlanTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void refactorPlanRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(RefactorPlanTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "code", "refactorGoal", "scope",
                        "compatibilityMode", "riskLevel", "outputFormat");
    }

    @Test
    void refactorPlanOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(RefactorPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("context")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("code")).isTrue();
    }

    @Test
    void refactorPlanLanguageEnumMatchesGenerate() throws Exception {
        JsonNode props = parse(RefactorPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void refactorPlanGoalEnumIsStable() throws Exception {
        JsonNode props = parse(RefactorPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("refactorGoal").path("enum")))
                .containsExactlyInAnyOrder("readability", "maintainability", "performance", "security",
                        "testability", "architecture", "migration", "api_compatibility", "all");
    }

    @Test
    void refactorPlanScopeEnumIsStable() throws Exception {
        JsonNode props = parse(RefactorPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("scope").path("enum")))
                .containsExactlyInAnyOrder("method", "class", "module", "multi_module", "documentation",
                        "build", "unknown");
    }

    @Test
    void refactorPlanCompatibilityModeEnumIsStable() throws Exception {
        JsonNode props = parse(RefactorPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("compatibilityMode").path("enum")))
                .containsExactlyInAnyOrder("preserve_behavior", "allow_behavior_change", "unknown");
    }

    @Test
    void refactorPlanOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(RefactorPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("refactor_plan", "migration_slices", "checklist", "adr_outline");
    }

    @Test
    void refactorPlanRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(RefactorPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void refactorPlanDescriptionStatesReadOnly() {
        String d = RefactorPlanTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run tests");
        assertThat(d).contains("apply patches");
    }

    @Test
    void explainDiffSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(ExplainDiffTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void explainDiffRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(ExplainDiffTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "diff", "diffFormat", "analysisFocus",
                        "riskLevel", "outputFormat");
    }

    @Test
    void explainDiffOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(ExplainDiffTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("context")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("diff")).isTrue();
    }

    @Test
    void explainDiffLanguageEnumMatchesGenerate() throws Exception {
        JsonNode props = parse(ExplainDiffTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void explainDiffFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ExplainDiffTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("diffFormat").path("enum")))
                .containsExactlyInAnyOrder("unified_diff", "git_diff", "patch", "plain_text", "unknown");
    }

    @Test
    void explainDiffAnalysisFocusEnumIsStable() throws Exception {
        JsonNode props = parse(ExplainDiffTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("analysisFocus").path("enum")))
                .containsExactlyInAnyOrder("correctness", "security", "performance", "tests",
                        "maintainability", "architecture", "migration", "all");
    }

    @Test
    void explainDiffOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ExplainDiffTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("diff_explanation", "risk_review", "checklist", "merge_review");
    }

    @Test
    void explainDiffRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(ExplainDiffTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void explainDiffDescriptionStatesReadOnly() {
        String d = ExplainDiffTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run tests");
        assertThat(d).contains("apply patches");
    }

    @Test
    void researchSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void researchRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "researchQuestion", "sourcePreference",
                        "freshness", "depth", "outputFormat", "riskLevel");
    }

    @Test
    void researchOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("context")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("researchQuestion")).isTrue();
    }

    @Test
    void researchSourcePreferenceEnumIsStable() throws Exception {
        JsonNode props = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("sourcePreference").path("enum")))
                .containsExactlyInAnyOrder("official_docs", "industry_best_practices", "academic",
                        "mixed");
    }

    @Test
    void researchFreshnessEnumIsStable() throws Exception {
        JsonNode props = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("freshness").path("enum")))
                .containsExactlyInAnyOrder("latest", "last_12_months", "stable");
    }

    @Test
    void researchDepthEnumIsStable() throws Exception {
        JsonNode props = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("depth").path("enum")))
                .containsExactlyInAnyOrder("quick", "standard", "deep");
    }

    @Test
    void researchOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("brief", "report", "decision_memo", "source_table");
    }

    @Test
    void researchRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(ResearchWithPerplexityTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void researchDescriptionStatesReadOnlyAndProviderRequirement() {
        String d = ResearchWithPerplexityTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read repository files");
        assertThat(d).contains("does not write files");
        assertThat(d).contains("does not execute commands");
        assertThat(d).contains("does not apply patches");
        assertThat(d).contains("perplexity_api_key");
    }

    @Test
    void analyzeBuildFailureSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void analyzeBuildFailureRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "failureLog", "failureType", "language",
                        "riskLevel", "outputFormat");
    }

    @Test
    void analyzeBuildFailureOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("relevantCode")).isTrue();
        assertThat(props.has("buildContext")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("failureLog")).isTrue();
    }

    @Test
    void analyzeBuildFailureLanguageEnumMatchesGenerate() throws Exception {
        JsonNode props = parse(AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void analyzeBuildFailureFailureTypeEnumIsStable() throws Exception {
        JsonNode props = parse(AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("failureType").path("enum")))
                .containsExactlyInAnyOrder("compile", "test", "gradle", "checkstyle", "spotbugs",
                        "static_analysis", "runtime", "unknown");
    }

    @Test
    void analyzeBuildFailureOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("diagnosis", "fix_plan", "checklist", "root_cause_analysis");
    }

    @Test
    void analyzeBuildFailureRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void analyzeBuildFailureDescriptionStatesReadOnly() {
        String d = AnalyzeBuildFailureTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run tests");
        assertThat(d).contains("apply patches");
    }

    @Test
    void designClassHierarchySchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void designClassHierarchyRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "domainContext", "designGoal", "scope",
                        "architectureStyle", "riskLevel", "outputFormat");
    }

    @Test
    void designClassHierarchyOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("existingTypes")).isTrue();
        assertThat(props.has("packageContext")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("domainContext")).isTrue();
    }

    @Test
    void designClassHierarchyLanguageEnumMatchesGenerate() throws Exception {
        JsonNode props = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void designClassHierarchyDesignGoalEnumIsStable() throws Exception {
        JsonNode props = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("designGoal").path("enum")))
                .containsExactlyInAnyOrder("extensibility", "testability", "api_compatibility",
                        "migration", "clean_architecture", "performance", "security",
                        "maintainability", "all");
    }

    @Test
    void designClassHierarchyScopeEnumIsStable() throws Exception {
        JsonNode props = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("scope").path("enum")))
                .containsExactlyInAnyOrder("package", "module", "starter", "library", "multi_module",
                        "unknown");
    }

    @Test
    void designClassHierarchyArchitectureStyleEnumIsStable() throws Exception {
        JsonNode props = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("architectureStyle").path("enum")))
                .containsExactlyInAnyOrder("clean_architecture", "hexagonal", "layered",
                        "spring_boot_starter", "plugin", "interceptor_pipeline", "domain_model",
                        "unknown");
    }

    @Test
    void designClassHierarchyOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("class_diagram", "design_proposal", "implementation_slices",
                        "adr_outline", "checklist");
    }

    @Test
    void designClassHierarchyRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(DesignClassHierarchyTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void designClassHierarchyDescriptionStatesReadOnly() {
        String d = DesignClassHierarchyTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run tests");
        assertThat(d).contains("apply patches");
    }

    @Test
    void reviewArchitectureSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void reviewArchitectureRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "architectureProposal", "reviewFocus",
                        "architectureScope", "architectureStyle", "compatibilityMode", "riskLevel",
                        "outputFormat");
    }

    @Test
    void reviewArchitectureOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("context")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("architectureProposal")).isTrue();
    }

    @Test
    void reviewArchitectureReviewFocusEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("reviewFocus").path("enum")))
                .containsExactlyInAnyOrder("api_compatibility", "observability", "security",
                        "migration", "testing", "performance", "operability", "maintainability",
                        "cost", "all");
    }

    @Test
    void reviewArchitectureScopeEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("architectureScope").path("enum")))
                .containsExactlyInAnyOrder("class", "package", "module", "multi_module", "platform",
                        "library", "starter", "unknown");
    }

    @Test
    void reviewArchitectureStyleEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("architectureStyle").path("enum")))
                .containsExactlyInAnyOrder("clean_architecture", "hexagonal", "layered",
                        "event_driven", "spring_boot_starter", "plugin", "interceptor_pipeline",
                        "observability_pipeline", "unknown");
    }

    @Test
    void reviewArchitectureCompatibilityModeEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("compatibilityMode").path("enum")))
                .containsExactlyInAnyOrder("preserve_api", "allow_breaking", "unknown");
    }

    @Test
    void reviewArchitectureOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("structured_review", "risk_matrix", "decision_memo",
                        "adr_review", "checklist");
    }

    @Test
    void reviewArchitectureRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewArchitectureTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void reviewArchitectureDescriptionStatesReadOnly() {
        String d = ReviewArchitectureTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run tests");
        assertThat(d).contains("apply patches");
    }

    @Test
    void writeMdxDocSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(WriteMdxDocTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void writeMdxDocRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(WriteMdxDocTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "docSubject", "targetAudience", "libraryContext",
                        "docType", "outputFormat", "riskLevel");
    }

    @Test
    void writeMdxDocOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(WriteMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("publicApi")).isTrue();
        assertThat(props.has("configurationProperties")).isTrue();
        assertThat(props.has("usageExamples")).isTrue();
        assertThat(props.has("docStyleContext")).isTrue();
        assertThat(props.has("mdxComponentsContext")).isTrue();
        assertThat(props.has("assetGuidelines")).isTrue();
        assertThat(props.has("constraints")).isTrue();
    }

    @Test
    void writeMdxDocTargetAudienceEnumIsStable() throws Exception {
        JsonNode props = parse(WriteMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("targetAudience").path("enum")))
                .containsExactlyInAnyOrder("platform_developers", "application_developers", "sre",
                        "architects", "mixed");
    }

    @Test
    void writeMdxDocDocTypeEnumIsStable() throws Exception {
        JsonNode props = parse(WriteMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("docType").path("enum")))
                .containsExactlyInAnyOrder("library_guide", "starter_guide", "migration_guide",
                        "how_to", "reference", "adr", "release_notes", "troubleshooting", "unknown");
    }

    @Test
    void writeMdxDocOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(WriteMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("mdx_page", "mdx_section", "outline",
                        "frontmatter_plus_body", "reviewable_draft");
    }

    @Test
    void writeMdxDocRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(WriteMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void writeMdxDocDescriptionStatesReadOnly() {
        String d = WriteMdxDocTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run docusaurus");
        assertThat(d).contains("apply patches");
    }

    @Test
    void reviewMdxDocToolNameIsStable() {
        assertThat(ReviewMdxDocTool.TOOL_NAME).isEqualTo("review_mdx_doc_with_opus");
    }

    @Test
    void reviewMdxDocSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void reviewMdxDocRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "mdxContent", "docSubject", "targetAudience",
                        "reviewFocus", "docType", "riskLevel", "outputFormat");
    }

    @Test
    void reviewMdxDocOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("libraryContext")).isTrue();
        assertThat(props.has("styleGuideContext")).isTrue();
        assertThat(props.has("mdxComponentsContext")).isTrue();
        assertThat(props.has("constraints")).isTrue();
    }

    @Test
    void reviewMdxDocTargetAudienceEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("targetAudience").path("enum")))
                .containsExactlyInAnyOrder("platform_developers", "application_developers", "sre",
                        "architects", "mixed");
    }

    @Test
    void reviewMdxDocReviewFocusEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("reviewFocus").path("enum")))
                .containsExactlyInAnyOrder("accuracy", "style", "structure", "examples",
                        "mdx_validity", "claims", "navigation", "accessibility", "all");
    }

    @Test
    void reviewMdxDocDocTypeEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("docType").path("enum")))
                .containsExactlyInAnyOrder("library_guide", "starter_guide", "migration_guide",
                        "how_to", "reference", "adr", "release_notes", "troubleshooting", "unknown");
    }

    @Test
    void reviewMdxDocOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("structured_review", "checklist", "risk_review",
                        "editorial_review", "publish_readiness");
    }

    @Test
    void reviewMdxDocRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewMdxDocTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void reviewMdxDocDescriptionStatesReadOnly() {
        String d = ReviewMdxDocTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("run docusaurus");
        assertThat(d).contains("apply patches");
    }

    @Test
    void generateMigrationPlanToolNameIsStable() {
        assertThat(GenerateMigrationPlanTool.TOOL_NAME).isEqualTo("generate_migration_plan_with_opus");
    }

    @Test
    void generateMigrationPlanSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void generateMigrationPlanRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "currentState", "targetState",
                        "compatibilityMode", "migrationScope", "migrationType", "riskLevel",
                        "outputFormat");
    }

    @Test
    void generateMigrationPlanOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("migrationContext")).isTrue();
        assertThat(props.has("constraints")).isTrue();
    }

    @Test
    void generateMigrationPlanLanguageEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "mdx", "gradle", "other");
    }

    @Test
    void generateMigrationPlanCompatibilityModeEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("compatibilityMode").path("enum")))
                .containsExactlyInAnyOrder("preserve_api", "preserve_behavior", "allow_breaking",
                        "unknown");
    }

    @Test
    void generateMigrationPlanScopeEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("migrationScope").path("enum")))
                .containsExactlyInAnyOrder("class", "package", "module", "multi_module", "platform",
                        "library", "starter", "documentation", "build", "unknown");
    }

    @Test
    void generateMigrationPlanTypeEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("migrationType").path("enum")))
                .containsExactlyInAnyOrder("framework_upgrade", "api_migration", "dependency_upgrade",
                        "architecture_migration", "configuration_migration", "documentation_migration",
                        "test_migration", "build_migration", "unknown");
    }

    @Test
    void generateMigrationPlanOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("migration_slices", "checklist", "risk_matrix",
                        "rollout_plan", "decision_memo");
    }

    @Test
    void generateMigrationPlanRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(GenerateMigrationPlanTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void generateMigrationPlanDescriptionStatesReadOnly() {
        String d = GenerateMigrationPlanTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("execute commands");
        assertThat(d).contains("upgrade dependencies");
        assertThat(d).contains("apply patches");
    }

    @Test
    void reviewTestsToolNameIsStable() {
        assertThat(ReviewTestsTool.TOOL_NAME).isEqualTo("review_tests_with_opus");
    }

    @Test
    void reviewTestsSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(ReviewTestsTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void reviewTestsRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(ReviewTestsTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "language", "testCode", "testIntent",
                        "testFramework", "testType", "reviewFocus", "riskLevel", "outputFormat");
    }

    @Test
    void reviewTestsOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(ReviewTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("productionContext")).isTrue();
        assertThat(props.has("failureLogs")).isTrue();
        assertThat(props.has("dependenciesContext")).isTrue();
        assertThat(props.has("constraints")).isTrue();
    }

    @Test
    void reviewTestsLanguageEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("language").path("enum")))
                .containsExactlyInAnyOrder("java", "go", "kotlin", "sql", "other");
    }

    @Test
    void reviewTestsFrameworkEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("testFramework").path("enum")))
                .containsExactlyInAnyOrder("junit5", "testng", "spock", "kotest", "go_testing",
                        "pytest", "unknown");
    }

    @Test
    void reviewTestsTypeEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("testType").path("enum")))
                .containsExactlyInAnyOrder("unit", "integration", "contract", "component", "slice",
                        "e2e", "property", "performance", "unknown");
    }

    @Test
    void reviewTestsReviewFocusEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("reviewFocus").path("enum")))
                .containsExactlyInAnyOrder("correctness", "coverage", "flakiness", "maintainability",
                        "assertions", "mocks", "integration_boundaries", "security", "performance",
                        "all");
    }

    @Test
    void reviewTestsOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("structured_review", "checklist", "risk_review",
                        "coverage_review", "ci_readiness");
    }

    @Test
    void reviewTestsRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewTestsTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void reviewTestsDescriptionStatesReadOnly() {
        String d = ReviewTestsTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("run tests");
        assertThat(d).contains("collect coverage");
        assertThat(d).contains("apply patches");
    }

    @Test
    void reviewGradleBuildToolNameIsStable() {
        assertThat(ReviewGradleBuildTool.TOOL_NAME).isEqualTo("review_gradle_build_with_opus");
    }

    @Test
    void reviewGradleBuildSchemaIsValidJsonObject() throws Exception {
        JsonNode schema = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void reviewGradleBuildRequiredFieldsAreStable() throws Exception {
        JsonNode schema = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON);
        assertThat(textValues(schema.path("required")))
                .containsExactlyInAnyOrder("task", "buildFilesContext", "projectType", "gradleDsl",
                        "reviewFocus", "riskLevel", "outputFormat");
    }

    @Test
    void reviewGradleBuildOptionalFieldsPresent() throws Exception {
        JsonNode props = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(props.has("settingsContext")).isTrue();
        assertThat(props.has("versionCatalogContext")).isTrue();
        assertThat(props.has("gradlePropertiesContext")).isTrue();
        assertThat(props.has("buildLogicContext")).isTrue();
        assertThat(props.has("dependencyContext")).isTrue();
        assertThat(props.has("buildFailureLogs")).isTrue();
        assertThat(props.has("constraints")).isTrue();
    }

    @Test
    void reviewGradleBuildProjectTypeEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("projectType").path("enum")))
                .containsExactlyInAnyOrder("java_library", "spring_boot_service",
                        "spring_boot_starter", "gradle_plugin", "multi_module_platform",
                        "documentation", "unknown");
    }

    @Test
    void reviewGradleBuildDslEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("gradleDsl").path("enum")))
                .containsExactlyInAnyOrder("groovy", "kotlin", "mixed", "unknown");
    }

    @Test
    void reviewGradleBuildReviewFocusEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("reviewFocus").path("enum")))
                .containsExactlyInAnyOrder("dependency_management", "plugin_configuration",
                        "configuration_cache", "task_graph", "multi_module_governance", "test_setup",
                        "publishing", "performance", "security", "all");
    }

    @Test
    void reviewGradleBuildOutputFormatEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("outputFormat").path("enum")))
                .containsExactlyInAnyOrder("structured_review", "checklist", "risk_review",
                        "build_health", "migration_review");
    }

    @Test
    void reviewGradleBuildRiskLevelEnumIsStable() throws Exception {
        JsonNode props = parse(ReviewGradleBuildTool.INPUT_SCHEMA_JSON).path("properties");
        assertThat(textValues(props.path("riskLevel").path("enum")))
                .containsExactlyInAnyOrder("low", "medium", "high");
    }

    @Test
    void reviewGradleBuildDescriptionStatesReadOnly() {
        String d = ReviewGradleBuildTool.DESCRIPTION.toLowerCase();
        assertThat(d).contains("read-only");
        assertThat(d).contains("does not read files");
        assertThat(d).contains("write files");
        assertThat(d).contains("run gradle/maven");
        assertThat(d).contains("resolve dependencies");
        assertThat(d).contains("publish artifacts");
        assertThat(d).contains("apply patches");
    }
}
