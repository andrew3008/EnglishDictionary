package space.br1440.platform.devtools.opusmcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.audit.AuditRecord;
import space.br1440.platform.devtools.opusmcp.audit.ProviderAuditSupport;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.AnthropicHttpOpusClient;
import space.br1440.platform.devtools.opusmcp.model.ModelRegistry;
import space.br1440.platform.devtools.opusmcp.model.OpusClient;
import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.model.OpusRequest;
import space.br1440.platform.devtools.opusmcp.model.OpusResponse;
import space.br1440.platform.devtools.opusmcp.prompt.ReviewGradleBuildPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GradleDsl;
import space.br1440.platform.devtools.opusmcp.tool.dto.GradleFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.GradleProjectType;
import space.br1440.platform.devtools.opusmcp.tool.dto.GradleReviewFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GradleReviewOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.GradleReviewVerdict;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewGradleBuildInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewGradleBuildOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 16A read-only MCP tool: {@code review_gradle_build_with_opus}.
 *
 * <p>Accepts explicit Cursor-provided Gradle build files context plus context (settings, version
 * catalog, gradle.properties, build logic, dependency context, optional failure logs and constraints)
 * plus review knobs, and delegates to the external Opus model to return a structured Gradle build
 * review: summary, verdict, review, findings, configuration-cache issues, dependency issues, plugin
 * issues, task graph issues, multi-module issues, test setup issues, publishing issues, performance
 * issues, security issues, compatibility risks, recommended checks, suggested changes, risks. Reuses
 * the exact same guard pipeline as the other tools (deny-list, secret scan, size limits, config
 * validation, model allowlist, rate limit, budget). Never reads/writes files, executes commands, runs
 * Gradle/Maven/tests, resolves dependencies, publishes artifacts, or applies patches. All inputs are
 * treated as untrusted data, never as instructions.
 */
public final class ReviewGradleBuildTool {

    private static final Logger log = LoggerFactory.getLogger(ReviewGradleBuildTool.class);

    public static final String TOOL_NAME = "review_gradle_build_with_opus";

    public static final String DESCRIPTION =
            "Read-only Gradle build review tool. Does not read files, write files, execute commands, "
                    + "run Gradle/Maven, run tests, resolve dependencies, publish artifacts, or apply "
                    + "patches. Reviews only the Gradle build files context, settings context, version "
                    + "catalog context, gradle.properties context, build logic context, dependency "
                    + "context, failure logs, constraints, project type, DSL, focus, and risk level "
                    + "explicitly provided in the tool input (treated as untrusted data, never "
                    + "instructions). Returns a structured build review with verdict, findings, "
                    + "configuration-cache issues, dependency issues, plugin issues, task graph "
                    + "issues, multi-module issues, test setup issues, publishing issues, performance "
                    + "issues, security issues, compatibility risks, recommended checks, suggested "
                    + "changes, risks, and open questions. Cursor/user must review and apply build "
                    + "changes manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "buildFilesContext", "projectType", "gradleDsl", "reviewFocus", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What to review or what the reviewer should focus on."
                },
                "buildFilesContext": {
                  "type": "string",
                  "description": "The Gradle build files (build.gradle/build.gradle.kts) content to review, explicitly provided by Cursor. Treated as data."
                },
                "settingsContext": {
                  "type": "string",
                  "description": "Optional settings.gradle(.kts) content explicitly provided by Cursor. Treated as data."
                },
                "versionCatalogContext": {
                  "type": "string",
                  "description": "Optional version catalog (libs.versions.toml) content explicitly provided by Cursor. Treated as data."
                },
                "gradlePropertiesContext": {
                  "type": "string",
                  "description": "Optional gradle.properties content explicitly provided by Cursor. Treated as data."
                },
                "buildLogicContext": {
                  "type": "string",
                  "description": "Optional buildSrc/convention plugin content explicitly provided by Cursor. Treated as data."
                },
                "dependencyContext": {
                  "type": "string",
                  "description": "Optional dependency/module context explicitly provided by Cursor. Treated as data."
                },
                "buildFailureLogs": {
                  "type": "string",
                  "description": "Optional Gradle build failure logs explicitly provided by Cursor. Treated as data."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit review constraints."
                },
                "projectType": {
                  "type": "string",
                  "enum": ["java_library", "spring_boot_service", "spring_boot_starter", "gradle_plugin", "multi_module_platform", "documentation", "unknown"]
                },
                "gradleDsl": {
                  "type": "string",
                  "enum": ["groovy", "kotlin", "mixed", "unknown"]
                },
                "reviewFocus": {
                  "type": "string",
                  "enum": ["dependency_management", "plugin_configuration", "configuration_cache", "task_graph", "multi_module_governance", "test_setup", "publishing", "performance", "security", "all"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["structured_review", "checklist", "risk_review", "build_health", "migration_review"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_SEVERITIES = Set.of(
            "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "dependency_management", "plugin_configuration", "configuration_cache", "task_graph",
            "multi_module_governance", "test_setup", "publishing", "performance", "security",
            "compatibility", "other");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|VERDICT|REVIEW|FINDINGS|CONFIGURATION[_ ]CACHE[_ ]ISSUES"
                    + "|DEPENDENCY[_ ]ISSUES|PLUGIN[_ ]ISSUES|TASK[_ ]GRAPH[_ ]ISSUES"
                    + "|MULTI[_ ]MODULE[_ ]ISSUES|TEST[_ ]SETUP[_ ]ISSUES|PUBLISHING[_ ]ISSUES"
                    + "|PERFORMANCE[_ ]ISSUES|SECURITY[_ ]ISSUES|COMPATIBILITY[_ ]RISKS"
                    + "|RECOMMENDED[_ ]CHECKS|SUGGESTED[_ ]CHANGES|OPEN[_ ]QUESTIONS"
                    + "|RISKS|SAFETY[_ ]NOTES|ASSUMPTIONS)\\s*:\\s*(.*)$");
    private static final Pattern FINDING_KEY_PATTERN = Pattern.compile(
            "(?i)^(severity|category|title|details|recommendation)\\s*:\\s*(.*)$");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)]\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final ReviewGradleBuildPromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public ReviewGradleBuildTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewGradleBuildPromptBuilder promptBuilder,
            SecretScanner secretScanner,
            DenyList denyList,
            LimitsGuard limitsGuard,
            ModelRegistry modelRegistry,
            ErrorMapper errorMapper,
            RateLimiter rateLimiter,
            BudgetTracker budgetTracker,
            AuditLogger auditLogger) {
        this(config, opusClient, promptBuilder, secretScanner, denyList, limitsGuard, modelRegistry,
                errorMapper, rateLimiter, budgetTracker, auditLogger, new ObjectMapper());
    }

    ReviewGradleBuildTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewGradleBuildPromptBuilder promptBuilder,
            SecretScanner secretScanner,
            DenyList denyList,
            LimitsGuard limitsGuard,
            ModelRegistry modelRegistry,
            ErrorMapper errorMapper,
            RateLimiter rateLimiter,
            BudgetTracker budgetTracker,
            AuditLogger auditLogger,
            ObjectMapper objectMapper) {
        this.config = config;
        this.opusClient = opusClient;
        this.promptBuilder = promptBuilder;
        this.secretScanner = secretScanner;
        this.denyList = denyList;
        this.limitsGuard = limitsGuard;
        this.modelRegistry = modelRegistry;
        this.errorMapper = errorMapper;
        this.rateLimiter = rateLimiter;
        this.budgetTracker = budgetTracker;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    public ReviewGradleBuildOutput handle(Map<String, Object> arguments) {
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String model = config.model();

        AuditRecord.Builder audit = AuditRecord.builder()
                .requestId(requestId)
                .timestamp(Instant.now().toString())
                .toolName(TOOL_NAME)
                .model(model)
                .budgetDecision("not-evaluated")
                .rateLimitDecision("not-evaluated")
                .httpStatusCategory("none");

        // 1. Validate input.
        Optional<ReviewGradleBuildInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, buildFilesContext, projectType, gradleDsl, "
                            + "reviewFocus, riskLevel, and outputFormat are required",
                    requestId,
                    model));
        }

        ReviewGradleBuildInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.buildFilesContext() + input.settingsContext()
                        + input.versionCatalogContext() + input.gradlePropertiesContext()
                        + input.buildLogicContext() + input.dependencyContext()
                        + input.buildFailureLogs() + input.constraints());
        audit.outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.BUDGET_EXCEEDED,
                    "Request rate limit exceeded; try again shortly",
                    requestId,
                    model));
        }

        // 8. Budget pre-check.
        BudgetTracker.BudgetDecision budgetDecision =
                budgetTracker.preCheck(inputChars, estimatedInputTokens);
        audit.budgetDecision(budgetDecision.allowed() ? "allowed" : budgetDecision.reason());
        if (!budgetDecision.allowed()) {
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofStatus(
                    GenerateCodeStatus.BUDGET_EXCEEDED,
                    "Daily budget exceeded: " + budgetDecision.reason(),
                    requestId,
                    model));
        }

        // 9-13. Build prompt, call model (with retry inside the client), parse, update budget, audit.
        try {
            String systemPrompt = promptBuilder.buildSystemPrompt(input);
            String userPrompt = promptBuilder.buildUserPrompt(input);
            OpusRequest request = new OpusRequest(model, config.maxTokens(), systemPrompt, userPrompt);
            OpusResponse response = opusClient.generate(request);
            ProviderAuditSupport.applySuccess(audit, response);

            LimitsGuard.TruncationResult truncated = limitsGuard.truncateOutput(response.text());
            ParsedReview review = parseReview(input, truncated.value());

            budgetTracker.record(inputChars,
                    response.inputTokenEstimate(), response.outputTokenEstimate());

            audit.estimatedInputTokens(response.inputTokenEstimate())
                    .estimatedOutputTokens(response.outputTokenEstimate())
                    .estimatedCost(budgetTracker.snapshot().estimatedCost())
                    .httpStatusCategory("2xx");

            return finish(audit, startNanos, new ReviewGradleBuildOutput(
                    GenerateCodeStatus.OK,
                    review.summary(),
                    review.verdict(),
                    review.review(),
                    review.findings(),
                    review.configurationCacheIssues(),
                    review.dependencyIssues(),
                    review.pluginIssues(),
                    review.taskGraphIssues(),
                    review.multiModuleIssues(),
                    review.testSetupIssues(),
                    review.publishingIssues(),
                    review.performanceIssues(),
                    review.securityIssues(),
                    review.compatibilityRisks(),
                    review.recommendedChecks(),
                    review.suggestedChanges(),
                    review.openQuestions(),
                    review.risks(),
                    review.safetyNotes(),
                    review.assumptions(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus review-gradle-build call failed requestId={} reason={}", requestId,
                    e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : GenerateCodeStatus.MODEL_ERROR;
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, ReviewGradleBuildOutput.ofProviderFailure(
                    status,
                    errorMapper.safeMessageForException(e),
                    e.reason(),
                    requestId,
                    model));
        }
    }

    public String handleAsJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(toPayload(handle(arguments)));
        } catch (JsonProcessingException e) {
            return "{\"status\":\"MODEL_ERROR\",\"summary\":\"Failed to serialize tool output\","
                    + "\"verdict\":\"NEEDS_MORE_CONTEXT\",\"findings\":[],\"review\":\"\"}";
        }
    }

    private ReviewGradleBuildOutput finish(
            AuditRecord.Builder audit, long startNanos, ReviewGradleBuildOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(ReviewGradleBuildInput input) {
        return (long) input.task().length() + input.buildFilesContext().length()
                + input.settingsContext().length() + input.versionCatalogContext().length()
                + input.gradlePropertiesContext().length() + input.buildLogicContext().length()
                + input.dependencyContext().length() + input.buildFailureLogs().length()
                + input.constraints().length();
    }

    private static String httpCategory(OpusClientException e) {
        if (e.reason() != OpusClientException.Reason.HTTP_ERROR) {
            return switch (e.reason()) {
                case TIMEOUT, NETWORK_ERROR -> "network";
                default -> "none";
            };
        }
        int s = e.httpStatus();
        if (s >= 500) {
            return "5xx";
        }
        if (s >= 400) {
            return "4xx";
        }
        return "other";
    }

    private Optional<ReviewGradleBuildInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String buildFilesContext = stringValue(arguments.get("buildFilesContext"));
        if (task == null || task.isBlank()
                || buildFilesContext == null || buildFilesContext.isBlank()) {
            return Optional.empty();
        }
        Optional<GradleProjectType> projectType =
                GradleProjectType.fromWire(stringValue(arguments.get("projectType")));
        Optional<GradleDsl> gradleDsl = GradleDsl.fromWire(stringValue(arguments.get("gradleDsl")));
        Optional<GradleReviewFocus> reviewFocus =
                GradleReviewFocus.fromWire(stringValue(arguments.get("reviewFocus")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        Optional<GradleReviewOutputFormat> outputFormat =
                GradleReviewOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (projectType.isEmpty() || gradleDsl.isEmpty() || reviewFocus.isEmpty()
                || riskLevel.isEmpty() || outputFormat.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReviewGradleBuildInput(
                task.trim(),
                buildFilesContext,
                nullToEmpty(stringValue(arguments.get("settingsContext"))),
                nullToEmpty(stringValue(arguments.get("versionCatalogContext"))),
                nullToEmpty(stringValue(arguments.get("gradlePropertiesContext"))),
                nullToEmpty(stringValue(arguments.get("buildLogicContext"))),
                nullToEmpty(stringValue(arguments.get("dependencyContext"))),
                nullToEmpty(stringValue(arguments.get("buildFailureLogs"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                projectType.get(),
                gradleDsl.get(),
                reviewFocus.get(),
                riskLevel.get(),
                outputFormat.get()));
    }

    private List<String> allTextFields(ReviewGradleBuildInput input) {
        return List.of(input.task(), input.buildFilesContext(), input.settingsContext(),
                input.versionCatalogContext(), input.gradlePropertiesContext(),
                input.buildLogicContext(), input.dependencyContext(), input.buildFailureLogs(),
                input.constraints());
    }

    private Optional<String> findDenyViolation(ReviewGradleBuildInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(ReviewGradleBuildInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(ReviewGradleBuildInput input) {
        for (String[] field : new String[][] {
                {"buildFilesContext", input.buildFilesContext()},
                {"settingsContext", input.settingsContext()},
                {"versionCatalogContext", input.versionCatalogContext()},
                {"gradlePropertiesContext", input.gradlePropertiesContext()},
                {"buildLogicContext", input.buildLogicContext()},
                {"dependencyContext", input.dependencyContext()},
                {"buildFailureLogs", input.buildFailureLogs()}}) {
            if (field[1].length() > limitsGuard.maxContextChars()) {
                return Optional.of(field[0] + " exceeds maximum size of "
                        + limitsGuard.maxContextChars() + " characters");
            }
        }
        LimitsGuard.OptionalLimit constraintsLimit = limitsGuard.checkConstraintsSize(input.constraints());
        if (constraintsLimit.exceeded()) {
            return Optional.of(constraintsLimit.message());
        }
        return Optional.empty();
    }

    private Map<String, Object> toPayload(ReviewGradleBuildOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("verdict", output.verdict());
        payload.put("review", output.review());
        List<Map<String, Object>> findings = new ArrayList<>();
        for (GradleFinding f : output.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", f.severity());
            m.put("category", f.category());
            m.put("title", f.title());
            m.put("details", f.details());
            m.put("recommendation", f.recommendation());
            findings.add(m);
        }
        payload.put("findings", findings);
        payload.put("configurationCacheIssues", output.configurationCacheIssues());
        payload.put("dependencyIssues", output.dependencyIssues());
        payload.put("pluginIssues", output.pluginIssues());
        payload.put("taskGraphIssues", output.taskGraphIssues());
        payload.put("multiModuleIssues", output.multiModuleIssues());
        payload.put("testSetupIssues", output.testSetupIssues());
        payload.put("publishingIssues", output.publishingIssues());
        payload.put("performanceIssues", output.performanceIssues());
        payload.put("securityIssues", output.securityIssues());
        payload.put("compatibilityRisks", output.compatibilityRisks());
        payload.put("recommendedChecks", output.recommendedChecks());
        payload.put("suggestedChanges", output.suggestedChanges());
        payload.put("openQuestions", output.openQuestions());
        payload.put("risks", output.risks());
        payload.put("safetyNotes", output.safetyNotes());
        payload.put("assumptions", output.assumptions());
        payload.put("truncated", output.truncated());
        payload.put("inputTokenEstimate", output.inputTokenEstimate());
        payload.put("outputTokenEstimate", output.outputTokenEstimate());
        payload.put("model", output.model());
        payload.put("requestId", output.requestId());
        return payload;
    }

    // ---- Defensive Gradle-build-review parsing -----------------------------------------------

    ParsedReview parseReview(ReviewGradleBuildInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String verdict = parseVerdict(sections.get("VERDICT"));
        String review = joinSection(sections.get("REVIEW"));
        List<GradleFinding> findings = parseFindings(sections.get("FINDINGS"));
        List<String> configurationCacheIssues = bullets(sections.get("CONFIGURATION_CACHE_ISSUES"));
        List<String> dependencyIssues = bullets(sections.get("DEPENDENCY_ISSUES"));
        List<String> pluginIssues = bullets(sections.get("PLUGIN_ISSUES"));
        List<String> taskGraphIssues = bullets(sections.get("TASK_GRAPH_ISSUES"));
        List<String> multiModuleIssues = bullets(sections.get("MULTI_MODULE_ISSUES"));
        List<String> testSetupIssues = bullets(sections.get("TEST_SETUP_ISSUES"));
        List<String> publishingIssues = bullets(sections.get("PUBLISHING_ISSUES"));
        List<String> performanceIssues = bullets(sections.get("PERFORMANCE_ISSUES"));
        List<String> securityIssues = bullets(sections.get("SECURITY_ISSUES"));
        List<String> compatibilityRisks = bullets(sections.get("COMPATIBILITY_RISKS"));
        List<String> recommendedChecks = bullets(sections.get("RECOMMENDED_CHECKS"));
        List<String> suggestedChanges = bullets(sections.get("SUGGESTED_CHANGES"));
        List<String> openQuestions = bullets(sections.get("OPEN_QUESTIONS"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));

        if (findings.isEmpty() && review.isBlank() && configurationCacheIssues.isEmpty()
                && dependencyIssues.isEmpty() && suggestedChanges.isEmpty()) {
            // Non-compliant response: keep the whole (truncated) text usable in review.
            review = text == null ? "" : text.strip();
        }
        return new ParsedReview(summary, verdict, review, findings, configurationCacheIssues,
                dependencyIssues, pluginIssues, taskGraphIssues, multiModuleIssues, testSetupIssues,
                publishingIssues, performanceIssues, securityIssues, compatibilityRisks,
                recommendedChecks, suggestedChanges, openQuestions, risks, safetyNotes, assumptions);
    }

    /** Splits the response into recognized sections, fence-aware. Returns raw body lines per section. */
    static Map<String, List<String>> splitSections(String text) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return sections;
        }
        String[] lines = text.split("\\R", -1);
        String current = null;
        boolean inFence = false;
        for (String raw : lines) {
            if (isFenceLine(raw)) {
                inFence = !inFence;
                if (current != null) {
                    sections.get(current).add(raw);
                }
                continue;
            }
            if (!inFence) {
                Matcher m = SECTION_HEADER_LINE_PATTERN.matcher(raw);
                if (m.matches()) {
                    current = normalizeSection(m.group(1));
                    sections.computeIfAbsent(current, k -> new ArrayList<>());
                    String inline = m.group(2) == null ? "" : m.group(2).trim();
                    if (!inline.isEmpty()) {
                        sections.get(current).add(inline);
                    }
                    continue;
                }
            }
            if (current != null) {
                sections.get(current).add(raw);
            }
        }
        return sections;
    }

    private String buildSummary(ReviewGradleBuildInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        return cap(input.projectType().wireValue() + " Gradle build review (focus "
                + input.reviewFocus().wireValue() + ", " + input.gradleDsl().wireValue() + " DSL): "
                + input.task());
    }

    private String parseVerdict(List<String> verdictLines) {
        if (verdictLines != null) {
            for (String line : verdictLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    Optional<GradleReviewVerdict> v = GradleReviewVerdict.fromWire(candidate);
                    if (v.isPresent()) {
                        return v.get().wireValue();
                    }
                    String firstToken = candidate.split("[\\sâ€”:\\-]")[0];
                    Optional<GradleReviewVerdict> vt = GradleReviewVerdict.fromWire(firstToken);
                    if (vt.isPresent()) {
                        return vt.get().wireValue();
                    }
                }
            }
        }
        return GradleReviewVerdict.NEEDS_MORE_CONTEXT.wireValue();
    }

    private static String joinSection(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines).strip();
    }

    private static List<String> bullets(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String raw : lines) {
            String item = raw.strip();
            if (item.isEmpty() || isFenceLine(item)) {
                continue;
            }
            if (item.startsWith("-") || item.startsWith("*")) {
                item = item.substring(1).strip();
            } else {
                Matcher numbered = NUMBERED_PREFIX.matcher(item);
                if (numbered.matches()) {
                    item = numbered.group(1).strip();
                }
            }
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Parses the FINDINGS block into structured findings. Defensive: a new finding starts at a
     * {@code severity:} line (optionally bulleted) or a bare bullet; unknown severities/categories
     * fall back to MEDIUM/other; a malformed entry never aborts parsing of the rest.
     */
    static List<GradleFinding> parseFindings(List<String> lines) {
        List<GradleFinding> findings = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return findings;
        }
        FindingBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || isFenceLine(line)) {
                continue;
            }
            boolean bullet = line.startsWith("-") || line.startsWith("*");
            String core = bullet ? line.substring(1).strip() : line;

            Matcher keyMatcher = FINDING_KEY_PATTERN.matcher(core);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1).toLowerCase();
                String value = keyMatcher.group(2) == null ? "" : keyMatcher.group(2).strip();
                if (key.equals("severity") && current != null && current.hasContent()) {
                    findings.add(current.build());
                    current = null;
                }
                if (current == null) {
                    current = new FindingBuilder();
                }
                current.set(key, value);
            } else if (bullet) {
                if (current != null && current.hasContent()) {
                    findings.add(current.build());
                }
                current = new FindingBuilder();
                current.set("title", core);
            } else if (current != null) {
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            findings.add(current.build());
        }
        return List.copyOf(findings);
    }

    private static String normalizeSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return GradleFinding.DEFAULT_SEVERITY;
        }
        String norm = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_SEVERITIES.contains(norm) ? norm : GradleFinding.DEFAULT_SEVERITY;
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return GradleFinding.DEFAULT_CATEGORY;
        }
        String norm = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_CATEGORIES.contains(norm) ? norm : GradleFinding.DEFAULT_CATEGORY;
    }

    private static boolean isFenceLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static String normalizeSection(String raw) {
        return raw == null ? "" : raw.trim().replace(' ', '_').toUpperCase();
    }

    private String sanitizeLine(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("```") || line.startsWith("~~~")) {
            return null;
        }
        String stripped = line.replaceFirst("^[-*#>\\s]+", "").strip();
        if (stripped.isEmpty() || stripped.matches("[{}\\[\\](),;:\"']+")) {
            return null;
        }
        return stripped;
    }

    private static final int SUMMARY_MAX_LENGTH = 200;

    private static String cap(String value) {
        String trimmed = value.strip();
        if (trimmed.length() > SUMMARY_MAX_LENGTH) {
            return trimmed.substring(0, SUMMARY_MAX_LENGTH).strip() + "...";
        }
        return trimmed;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    record ParsedReview(
            String summary,
            String verdict,
            String review,
            List<GradleFinding> findings,
            List<String> configurationCacheIssues,
            List<String> dependencyIssues,
            List<String> pluginIssues,
            List<String> taskGraphIssues,
            List<String> multiModuleIssues,
            List<String> testSetupIssues,
            List<String> publishingIssues,
            List<String> performanceIssues,
            List<String> securityIssues,
            List<String> compatibilityRisks,
            List<String> recommendedChecks,
            List<String> suggestedChanges,
            List<String> openQuestions,
            List<String> risks,
            List<String> safetyNotes,
            List<String> assumptions) {
    }

    private static final class FindingBuilder {
        private String severity;
        private String category;
        private String title;
        private String details;
        private String recommendation;

        void set(String key, String value) {
            switch (key) {
                case "severity" -> severity = value;
                case "category" -> category = value;
                case "title" -> title = value;
                case "details" -> details = value;
                case "recommendation" -> recommendation = value;
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        void appendContinuation(String text) {
            details = append(details, text);
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return isNonBlank(severity) || isNonBlank(category) || isNonBlank(title)
                    || isNonBlank(details) || isNonBlank(recommendation);
        }

        GradleFinding build() {
            String t = title != null ? title : (details != null ? details : "Finding");
            return new GradleFinding(
                    normalizeSeverity(severity),
                    normalizeCategory(category),
                    t,
                    details == null ? "" : details,
                    recommendation == null ? "" : recommendation);
        }
    }
}
