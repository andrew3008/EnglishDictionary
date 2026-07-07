package space.br1440.platform.devtools.opusmcp.server;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.AnthropicHttpOpusClient;
import space.br1440.platform.devtools.opusmcp.model.ModelRegistry;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityConfig;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityResearchClient;
import space.br1440.platform.devtools.opusmcp.prompt.AnalyzeBuildFailurePromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.DesignClassHierarchyPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.ExplainDiffPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.GenerateTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.PromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.RefactorPlanPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.ResearchPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.GenerateMigrationPlanPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.ReviewArchitecturePromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.ReviewGradleBuildPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.ReviewMdxDocPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.ReviewTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.ReviewPromptBuilder;
import space.br1440.platform.devtools.opusmcp.prompt.WriteMdxDocPromptBuilder;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.tool.AnalyzeBuildFailureTool;
import space.br1440.platform.devtools.opusmcp.tool.DesignClassHierarchyTool;
import space.br1440.platform.devtools.opusmcp.tool.EchoMcpConnectionTool;
import space.br1440.platform.devtools.opusmcp.tool.ExplainDiffTool;
import space.br1440.platform.devtools.opusmcp.tool.GenerateCodeTool;
import space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool;
import space.br1440.platform.devtools.opusmcp.tool.RefactorPlanTool;
import space.br1440.platform.devtools.opusmcp.tool.ResearchWithPerplexityTool;
import space.br1440.platform.devtools.opusmcp.tool.GenerateMigrationPlanTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewArchitectureTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewCodeTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewGradleBuildTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewMdxDocTool;
import space.br1440.platform.devtools.opusmcp.tool.ReviewTestsTool;
import space.br1440.platform.devtools.opusmcp.tool.WriteMdxDocTool;

import java.util.Map;

/**
 * Builds the read-only stdio MCP server (Phase 0A / Phase 1 / Phase 2 / Phase 7A).
 *
 * <p>Exposes {@code echo_mcp_connection}, {@code generate_code_with_opus},
 * {@code review_code_with_opus}, {@code generate_tests_with_opus},
 * {@code refactor_plan_with_opus}, {@code explain_diff_with_opus},
 * {@code research_with_perplexity}, {@code analyze_build_failure_with_opus},
 * {@code design_class_hierarchy_with_opus}, {@code review_architecture_with_opus},
 * {@code write_mdx_doc_with_opus}, {@code review_mdx_doc_with_opus},
 * {@code generate_migration_plan_with_opus}, {@code review_tests_with_opus}, and
 * {@code review_gradle_build_with_opus}. The server never reads/writes files, runs commands, or
 * applies patches.
 */
public final class McpServerFactory {

    public static final String SERVER_NAME = EchoMcpConnectionTool.SERVER_NAME;
    public static final String SERVER_VERSION = "0.2.0";

    private final EchoMcpConnectionTool echoTool;
    private final GenerateCodeTool generateCodeTool;
    private final ReviewCodeTool reviewCodeTool;
    private final GenerateTestsTool generateTestsTool;
    private final RefactorPlanTool refactorPlanTool;
    private final ExplainDiffTool explainDiffTool;
    private final ResearchWithPerplexityTool researchTool;
    private final AnalyzeBuildFailureTool analyzeBuildFailureTool;
    private final DesignClassHierarchyTool designClassHierarchyTool;
    private final ReviewArchitectureTool reviewArchitectureTool;
    private final WriteMdxDocTool writeMdxDocTool;
    private final ReviewMdxDocTool reviewMdxDocTool;
    private final GenerateMigrationPlanTool generateMigrationPlanTool;
    private final ReviewTestsTool reviewTestsTool;
    private final ReviewGradleBuildTool reviewGradleBuildTool;

    public McpServerFactory() {
        this(createDefaultGenerateCodeTool(), createDefaultReviewCodeTool(),
                createDefaultGenerateTestsTool(), createDefaultRefactorPlanTool(),
                createDefaultExplainDiffTool());
    }

    public McpServerFactory(GenerateCodeTool generateCodeTool) {
        this(generateCodeTool, createDefaultReviewCodeTool(), createDefaultGenerateTestsTool(),
                createDefaultRefactorPlanTool(), createDefaultExplainDiffTool());
    }

    public McpServerFactory(GenerateCodeTool generateCodeTool, ReviewCodeTool reviewCodeTool) {
        this(generateCodeTool, reviewCodeTool, createDefaultGenerateTestsTool(),
                createDefaultRefactorPlanTool(), createDefaultExplainDiffTool());
    }

    public McpServerFactory(GenerateCodeTool generateCodeTool, ReviewCodeTool reviewCodeTool,
            GenerateTestsTool generateTestsTool) {
        this(generateCodeTool, reviewCodeTool, generateTestsTool, createDefaultRefactorPlanTool(),
                createDefaultExplainDiffTool());
    }

    public McpServerFactory(GenerateCodeTool generateCodeTool, ReviewCodeTool reviewCodeTool,
            GenerateTestsTool generateTestsTool, RefactorPlanTool refactorPlanTool) {
        this(generateCodeTool, reviewCodeTool, generateTestsTool, refactorPlanTool,
                createDefaultExplainDiffTool());
    }

    public McpServerFactory(GenerateCodeTool generateCodeTool, ReviewCodeTool reviewCodeTool,
            GenerateTestsTool generateTestsTool, RefactorPlanTool refactorPlanTool,
            ExplainDiffTool explainDiffTool) {
        this(new EchoMcpConnectionTool(), generateCodeTool, reviewCodeTool, generateTestsTool,
                refactorPlanTool, explainDiffTool, createDefaultResearchTool(),
                createDefaultAnalyzeBuildFailureTool(), createDefaultDesignClassHierarchyTool(),
                createDefaultReviewArchitectureTool(), createDefaultWriteMdxDocTool(),
                createDefaultReviewMdxDocTool(), createDefaultGenerateMigrationPlanTool(),
                createDefaultReviewTestsTool(), createDefaultReviewGradleBuildTool());
    }

    McpServerFactory(EchoMcpConnectionTool echoTool, GenerateCodeTool generateCodeTool,
            ReviewCodeTool reviewCodeTool, GenerateTestsTool generateTestsTool,
            RefactorPlanTool refactorPlanTool, ExplainDiffTool explainDiffTool,
            ResearchWithPerplexityTool researchTool,
            AnalyzeBuildFailureTool analyzeBuildFailureTool,
            DesignClassHierarchyTool designClassHierarchyTool,
            ReviewArchitectureTool reviewArchitectureTool,
            WriteMdxDocTool writeMdxDocTool,
            ReviewMdxDocTool reviewMdxDocTool,
            GenerateMigrationPlanTool generateMigrationPlanTool,
            ReviewTestsTool reviewTestsTool,
            ReviewGradleBuildTool reviewGradleBuildTool) {
        this.echoTool = echoTool;
        this.generateCodeTool = generateCodeTool;
        this.reviewCodeTool = reviewCodeTool;
        this.generateTestsTool = generateTestsTool;
        this.refactorPlanTool = refactorPlanTool;
        this.explainDiffTool = explainDiffTool;
        this.researchTool = researchTool;
        this.analyzeBuildFailureTool = analyzeBuildFailureTool;
        this.designClassHierarchyTool = designClassHierarchyTool;
        this.reviewArchitectureTool = reviewArchitectureTool;
        this.writeMdxDocTool = writeMdxDocTool;
        this.reviewMdxDocTool = reviewMdxDocTool;
        this.generateMigrationPlanTool = generateMigrationPlanTool;
        this.reviewTestsTool = reviewTestsTool;
        this.reviewGradleBuildTool = reviewGradleBuildTool;
    }

    private static GenerateCodeTool createDefaultGenerateCodeTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new GenerateCodeTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new PromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static ReviewCodeTool createDefaultReviewCodeTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new ReviewCodeTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new ReviewPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static GenerateTestsTool createDefaultGenerateTestsTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new GenerateTestsTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new GenerateTestsPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static RefactorPlanTool createDefaultRefactorPlanTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new RefactorPlanTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new RefactorPlanPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static ExplainDiffTool createDefaultExplainDiffTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new ExplainDiffTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new ExplainDiffPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static AnalyzeBuildFailureTool createDefaultAnalyzeBuildFailureTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new AnalyzeBuildFailureTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new AnalyzeBuildFailurePromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static DesignClassHierarchyTool createDefaultDesignClassHierarchyTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new DesignClassHierarchyTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new DesignClassHierarchyPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static ReviewArchitectureTool createDefaultReviewArchitectureTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new ReviewArchitectureTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new ReviewArchitecturePromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static WriteMdxDocTool createDefaultWriteMdxDocTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new WriteMdxDocTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new WriteMdxDocPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static ReviewMdxDocTool createDefaultReviewMdxDocTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new ReviewMdxDocTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new ReviewMdxDocPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static GenerateMigrationPlanTool createDefaultGenerateMigrationPlanTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new GenerateMigrationPlanTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new GenerateMigrationPlanPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static ReviewTestsTool createDefaultReviewTestsTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new ReviewTestsTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new ReviewTestsPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static ReviewGradleBuildTool createDefaultReviewGradleBuildTool() {
        AppConfig config = AppConfig.fromEnv();
        ModelRegistry modelRegistry = new ModelRegistry();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new ReviewGradleBuildTool(
                config,
                new AnthropicHttpOpusClient(config, modelRegistry),
                new ReviewGradleBuildPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                modelRegistry,
                new ErrorMapper(),
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    private static ResearchWithPerplexityTool createDefaultResearchTool() {
        AppConfig config = AppConfig.fromEnv();
        PerplexityConfig perplexityConfig = PerplexityConfig.fromEnv();
        LimitsGuard limitsGuard = new LimitsGuard(
                config.maxContextChars(),
                config.maxConstraintsChars(),
                config.maxOutputChars());
        BudgetTracker budgetTracker = new BudgetTracker(new BudgetTracker.BudgetLimits(
                config.dailyRequestLimit(),
                config.dailyInputCharLimit(),
                config.dailyEstimatedTokenLimit(),
                config.dailyCostLimit(),
                config.pricePer1kInputTokens(),
                config.pricePer1kOutputTokens()));
        return new ResearchWithPerplexityTool(
                perplexityConfig,
                new PerplexityResearchClient(perplexityConfig),
                new ResearchPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                limitsGuard,
                new RateLimiter(config.requestsPerMinute()),
                budgetTracker,
                new AuditLogger(config.auditIncludeContent()));
    }

    public McpSyncServer create() {
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);

        McpSchema.Tool echoToolDef = McpSchema.Tool
                .builder(EchoMcpConnectionTool.TOOL_NAME, jsonMapper, EchoMcpConnectionTool.INPUT_SCHEMA_JSON)
                .description(EchoMcpConnectionTool.DESCRIPTION)
                .build();

        McpSchema.Tool generateToolDef = McpSchema.Tool
                .builder(GenerateCodeTool.TOOL_NAME, jsonMapper, GenerateCodeTool.INPUT_SCHEMA_JSON)
                .description(GenerateCodeTool.DESCRIPTION)
                .build();

        McpSchema.Tool reviewToolDef = McpSchema.Tool
                .builder(ReviewCodeTool.TOOL_NAME, jsonMapper, ReviewCodeTool.INPUT_SCHEMA_JSON)
                .description(ReviewCodeTool.DESCRIPTION)
                .build();

        McpSchema.Tool generateTestsToolDef = McpSchema.Tool
                .builder(GenerateTestsTool.TOOL_NAME, jsonMapper, GenerateTestsTool.INPUT_SCHEMA_JSON)
                .description(GenerateTestsTool.DESCRIPTION)
                .build();

        McpSchema.Tool refactorPlanToolDef = McpSchema.Tool
                .builder(RefactorPlanTool.TOOL_NAME, jsonMapper, RefactorPlanTool.INPUT_SCHEMA_JSON)
                .description(RefactorPlanTool.DESCRIPTION)
                .build();

        McpSchema.Tool explainDiffToolDef = McpSchema.Tool
                .builder(ExplainDiffTool.TOOL_NAME, jsonMapper, ExplainDiffTool.INPUT_SCHEMA_JSON)
                .description(ExplainDiffTool.DESCRIPTION)
                .build();

        McpSchema.Tool researchToolDef = McpSchema.Tool
                .builder(ResearchWithPerplexityTool.TOOL_NAME, jsonMapper,
                        ResearchWithPerplexityTool.INPUT_SCHEMA_JSON)
                .description(ResearchWithPerplexityTool.DESCRIPTION)
                .build();

        McpSchema.Tool analyzeBuildFailureToolDef = McpSchema.Tool
                .builder(AnalyzeBuildFailureTool.TOOL_NAME, jsonMapper,
                        AnalyzeBuildFailureTool.INPUT_SCHEMA_JSON)
                .description(AnalyzeBuildFailureTool.DESCRIPTION)
                .build();

        McpSchema.Tool designClassHierarchyToolDef = McpSchema.Tool
                .builder(DesignClassHierarchyTool.TOOL_NAME, jsonMapper,
                        DesignClassHierarchyTool.INPUT_SCHEMA_JSON)
                .description(DesignClassHierarchyTool.DESCRIPTION)
                .build();

        McpSchema.Tool reviewArchitectureToolDef = McpSchema.Tool
                .builder(ReviewArchitectureTool.TOOL_NAME, jsonMapper,
                        ReviewArchitectureTool.INPUT_SCHEMA_JSON)
                .description(ReviewArchitectureTool.DESCRIPTION)
                .build();

        McpSchema.Tool writeMdxDocToolDef = McpSchema.Tool
                .builder(WriteMdxDocTool.TOOL_NAME, jsonMapper,
                        WriteMdxDocTool.INPUT_SCHEMA_JSON)
                .description(WriteMdxDocTool.DESCRIPTION)
                .build();

        McpSchema.Tool reviewMdxDocToolDef = McpSchema.Tool
                .builder(ReviewMdxDocTool.TOOL_NAME, jsonMapper,
                        ReviewMdxDocTool.INPUT_SCHEMA_JSON)
                .description(ReviewMdxDocTool.DESCRIPTION)
                .build();

        McpSchema.Tool generateMigrationPlanToolDef = McpSchema.Tool
                .builder(GenerateMigrationPlanTool.TOOL_NAME, jsonMapper,
                        GenerateMigrationPlanTool.INPUT_SCHEMA_JSON)
                .description(GenerateMigrationPlanTool.DESCRIPTION)
                .build();

        McpSchema.Tool reviewTestsToolDef = McpSchema.Tool
                .builder(ReviewTestsTool.TOOL_NAME, jsonMapper,
                        ReviewTestsTool.INPUT_SCHEMA_JSON)
                .description(ReviewTestsTool.DESCRIPTION)
                .build();

        McpSchema.Tool reviewGradleBuildToolDef = McpSchema.Tool
                .builder(ReviewGradleBuildTool.TOOL_NAME, jsonMapper,
                        ReviewGradleBuildTool.INPUT_SCHEMA_JSON)
                .description(ReviewGradleBuildTool.DESCRIPTION)
                .build();

        return McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .toolCall(echoToolDef, this::handleEcho)
                .toolCall(generateToolDef, this::handleGenerate)
                .toolCall(reviewToolDef, this::handleReview)
                .toolCall(generateTestsToolDef, this::handleGenerateTests)
                .toolCall(refactorPlanToolDef, this::handleRefactorPlan)
                .toolCall(explainDiffToolDef, this::handleExplainDiff)
                .toolCall(researchToolDef, this::handleResearch)
                .toolCall(analyzeBuildFailureToolDef, this::handleAnalyzeBuildFailure)
                .toolCall(designClassHierarchyToolDef, this::handleDesignClassHierarchy)
                .toolCall(reviewArchitectureToolDef, this::handleReviewArchitecture)
                .toolCall(writeMdxDocToolDef, this::handleWriteMdxDoc)
                .toolCall(reviewMdxDocToolDef, this::handleReviewMdxDoc)
                .toolCall(generateMigrationPlanToolDef, this::handleGenerateMigrationPlan)
                .toolCall(reviewTestsToolDef, this::handleReviewTests)
                .toolCall(reviewGradleBuildToolDef, this::handleReviewGradleBuild)
                .build();
    }

    private McpSchema.CallToolResult handleEcho(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Object rawMessage = request.arguments() == null ? null : request.arguments().get("message");
        String message = rawMessage == null ? null : rawMessage.toString();
        String json = echoTool.handleAsJson(message);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleGenerate(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = generateCodeTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleReview(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = reviewCodeTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleGenerateTests(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = generateTestsTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleRefactorPlan(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = refactorPlanTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleExplainDiff(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = explainDiffTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleResearch(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = researchTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleAnalyzeBuildFailure(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = analyzeBuildFailureTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleDesignClassHierarchy(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = designClassHierarchyTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleReviewArchitecture(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = reviewArchitectureTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleWriteMdxDoc(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = writeMdxDocTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleReviewMdxDoc(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = reviewMdxDocTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleGenerateMigrationPlan(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = generateMigrationPlanTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleReviewTests(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = reviewTestsTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }

    private McpSchema.CallToolResult handleReviewGradleBuild(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String json = reviewGradleBuildTool.handleAsJson(arguments);
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .build();
    }
}
