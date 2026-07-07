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
import space.br1440.platform.devtools.opusmcp.prompt.GenerateMigrationPlanPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateMigrationPlanInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateMigrationPlanOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.MigrationCompatibilityMode;
import space.br1440.platform.devtools.opusmcp.tool.dto.MigrationOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.MigrationScope;
import space.br1440.platform.devtools.opusmcp.tool.dto.MigrationSlice;
import space.br1440.platform.devtools.opusmcp.tool.dto.MigrationType;
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
 * Phase 14A read-only MCP tool: {@code generate_migration_plan_with_opus}.
 *
 * <p>Accepts explicit Cursor-provided current state, target state, optional migration context and
 * constraints plus migration knobs, and delegates to the external Opus model to return a structured
 * migration plan: summary, overview, migration slices (id/title/goal/changes/verification/risk/
 * rollback), compatibility notes, breaking risks, dependency and configuration changes, test plan,
 * observability checks, rollout and rollback plans, docs updates, open questions, risks. Reuses the
 * exact same guard pipeline as the other tools (deny-list, secret scan, size limits, config
 * validation, model allowlist, rate limit, budget). Never reads/writes files, upgrades dependencies,
 * executes commands, runs Gradle/tests, or applies patches. All inputs are treated as untrusted data,
 * never as instructions.
 */
public final class GenerateMigrationPlanTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateMigrationPlanTool.class);

    public static final String TOOL_NAME = "generate_migration_plan_with_opus";

    public static final String DESCRIPTION =
            "Read-only migration planning tool. Does not read files, write files, execute commands, "
                    + "run tests, upgrade dependencies, or apply patches. Plans only from the current "
                    + "state, target state, migration context, constraints, compatibility mode, scope, "
                    + "and migration type explicitly provided in the tool input (treated as untrusted "
                    + "data, never instructions). Returns structured migration slices, compatibility "
                    + "notes, breaking risks, dependency/configuration changes, test plan, "
                    + "observability checks, rollout plan, rollback plan, docs updates, risks, and open "
                    + "questions. Cursor/user must review, implement, and verify manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "language", "currentState", "targetState", "compatibilityMode", "migrationScope", "migrationType", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What to migrate or what the plan should focus on."
                },
                "language": {
                  "type": "string",
                  "enum": ["java", "go", "kotlin", "sql", "mdx", "gradle", "other"]
                },
                "currentState": {
                  "type": "string",
                  "description": "The current state to migrate from, explicitly provided by Cursor. Treated as data."
                },
                "targetState": {
                  "type": "string",
                  "description": "The desired target state, explicitly provided by Cursor. Treated as data."
                },
                "migrationContext": {
                  "type": "string",
                  "description": "Optional curated migration context explicitly provided by Cursor. Treated as data."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit migration constraints."
                },
                "compatibilityMode": {
                  "type": "string",
                  "enum": ["preserve_api", "preserve_behavior", "allow_breaking", "unknown"]
                },
                "migrationScope": {
                  "type": "string",
                  "enum": ["class", "package", "module", "multi_module", "platform", "library", "starter", "documentation", "build", "unknown"]
                },
                "migrationType": {
                  "type": "string",
                  "enum": ["framework_upgrade", "api_migration", "dependency_upgrade", "architecture_migration", "configuration_migration", "documentation_migration", "test_migration", "build_migration", "unknown"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["migration_slices", "checklist", "risk_matrix", "rollout_plan", "decision_memo"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_RISKS = Set.of("LOW", "MEDIUM", "HIGH");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|MIGRATION[_ ]OVERVIEW|MIGRATION[_ ]SLICES"
                    + "|COMPATIBILITY[_ ]NOTES|BREAKING[_ ]RISKS|DEPENDENCY[_ ]CHANGES"
                    + "|CONFIGURATION[_ ]CHANGES|TEST[_ ]PLAN|OBSERVABILITY[_ ]CHECKS"
                    + "|ROLLOUT[_ ]PLAN|ROLLBACK[_ ]PLAN|DOCS[_ ]UPDATES|OPEN[_ ]QUESTIONS"
                    + "|RISKS|SAFETY[_ ]NOTES|ASSUMPTIONS)\\s*:\\s*(.*)$");
    private static final Pattern SLICE_KEY_PATTERN = Pattern.compile(
            "(?i)^(id|title|goal|changes|verification|risk|rollback)\\s*:\\s*(.*)$");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)]\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final GenerateMigrationPlanPromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public GenerateMigrationPlanTool(
            AppConfig config,
            OpusClient opusClient,
            GenerateMigrationPlanPromptBuilder promptBuilder,
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

    GenerateMigrationPlanTool(
            AppConfig config,
            OpusClient opusClient,
            GenerateMigrationPlanPromptBuilder promptBuilder,
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

    public GenerateMigrationPlanOutput handle(Map<String, Object> arguments) {
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
        Optional<GenerateMigrationPlanInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, language, currentState, targetState, "
                            + "compatibilityMode, migrationScope, migrationType, riskLevel, and "
                            + "outputFormat are required",
                    requestId,
                    model));
        }

        GenerateMigrationPlanInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.currentState() + input.targetState() + input.migrationContext()
                        + input.constraints());
        audit.outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
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
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
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
            ParsedPlan plan = parsePlan(input, truncated.value());

            budgetTracker.record(inputChars,
                    response.inputTokenEstimate(), response.outputTokenEstimate());

            audit.estimatedInputTokens(response.inputTokenEstimate())
                    .estimatedOutputTokens(response.outputTokenEstimate())
                    .estimatedCost(budgetTracker.snapshot().estimatedCost())
                    .httpStatusCategory("2xx");

            return finish(audit, startNanos, new GenerateMigrationPlanOutput(
                    GenerateCodeStatus.OK,
                    plan.summary(),
                    plan.migrationOverview(),
                    plan.migrationSlices(),
                    plan.compatibilityNotes(),
                    plan.breakingRisks(),
                    plan.dependencyChanges(),
                    plan.configurationChanges(),
                    plan.testPlan(),
                    plan.observabilityChecks(),
                    plan.rolloutPlan(),
                    plan.rollbackPlan(),
                    plan.docsUpdates(),
                    plan.openQuestions(),
                    plan.risks(),
                    plan.safetyNotes(),
                    plan.assumptions(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus migration-plan call failed requestId={} reason={}", requestId, e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : GenerateCodeStatus.MODEL_ERROR;
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, GenerateMigrationPlanOutput.ofStatus(
                    status,
                    errorMapper.safeMessageForException(e),
                    requestId,
                    model));
        }
    }

    public String handleAsJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(toPayload(handle(arguments)));
        } catch (JsonProcessingException e) {
            return "{\"status\":\"MODEL_ERROR\",\"summary\":\"Failed to serialize tool output\","
                    + "\"migrationOverview\":\"\",\"migrationSlices\":[]}";
        }
    }

    private GenerateMigrationPlanOutput finish(
            AuditRecord.Builder audit, long startNanos, GenerateMigrationPlanOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(GenerateMigrationPlanInput input) {
        return (long) input.task().length() + input.currentState().length()
                + input.targetState().length() + input.migrationContext().length()
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

    private Optional<GenerateMigrationPlanInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String currentState = stringValue(arguments.get("currentState"));
        String targetState = stringValue(arguments.get("targetState"));
        if (task == null || task.isBlank() || currentState == null || currentState.isBlank()
                || targetState == null || targetState.isBlank()) {
            return Optional.empty();
        }
        Optional<CodeLanguage> language = CodeLanguage.fromWire(stringValue(arguments.get("language")));
        Optional<MigrationCompatibilityMode> compatibilityMode =
                MigrationCompatibilityMode.fromWire(stringValue(arguments.get("compatibilityMode")));
        Optional<MigrationScope> migrationScope =
                MigrationScope.fromWire(stringValue(arguments.get("migrationScope")));
        Optional<MigrationType> migrationType =
                MigrationType.fromWire(stringValue(arguments.get("migrationType")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        Optional<MigrationOutputFormat> outputFormat =
                MigrationOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (language.isEmpty() || compatibilityMode.isEmpty() || migrationScope.isEmpty()
                || migrationType.isEmpty() || riskLevel.isEmpty() || outputFormat.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GenerateMigrationPlanInput(
                task.trim(),
                language.get(),
                currentState,
                targetState,
                nullToEmpty(stringValue(arguments.get("migrationContext"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                compatibilityMode.get(),
                migrationScope.get(),
                migrationType.get(),
                riskLevel.get(),
                outputFormat.get()));
    }

    private List<String> allTextFields(GenerateMigrationPlanInput input) {
        return List.of(input.task(), input.currentState(), input.targetState(),
                input.migrationContext(), input.constraints());
    }

    private Optional<String> findDenyViolation(GenerateMigrationPlanInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(GenerateMigrationPlanInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(GenerateMigrationPlanInput input) {
        for (String[] field : new String[][] {
                {"currentState", input.currentState()},
                {"targetState", input.targetState()},
                {"migrationContext", input.migrationContext()}}) {
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

    private Map<String, Object> toPayload(GenerateMigrationPlanOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("migrationOverview", output.migrationOverview());
        List<Map<String, Object>> slices = new ArrayList<>();
        for (MigrationSlice s : output.migrationSlices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("title", s.title());
            m.put("goal", s.goal());
            m.put("changes", s.changes());
            m.put("verification", s.verification());
            m.put("risk", s.risk());
            m.put("rollback", s.rollback());
            slices.add(m);
        }
        payload.put("migrationSlices", slices);
        payload.put("compatibilityNotes", output.compatibilityNotes());
        payload.put("breakingRisks", output.breakingRisks());
        payload.put("dependencyChanges", output.dependencyChanges());
        payload.put("configurationChanges", output.configurationChanges());
        payload.put("testPlan", output.testPlan());
        payload.put("observabilityChecks", output.observabilityChecks());
        payload.put("rolloutPlan", output.rolloutPlan());
        payload.put("rollbackPlan", output.rollbackPlan());
        payload.put("docsUpdates", output.docsUpdates());
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

    // ---- Defensive migration-plan parsing -----------------------------------------------------

    ParsedPlan parsePlan(GenerateMigrationPlanInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String migrationOverview = joinSection(sections.get("MIGRATION_OVERVIEW"));
        List<MigrationSlice> migrationSlices = parseSlices(sections.get("MIGRATION_SLICES"));
        List<String> compatibilityNotes = bullets(sections.get("COMPATIBILITY_NOTES"));
        List<String> breakingRisks = bullets(sections.get("BREAKING_RISKS"));
        List<String> dependencyChanges = bullets(sections.get("DEPENDENCY_CHANGES"));
        List<String> configurationChanges = bullets(sections.get("CONFIGURATION_CHANGES"));
        List<String> testPlan = bullets(sections.get("TEST_PLAN"));
        List<String> observabilityChecks = bullets(sections.get("OBSERVABILITY_CHECKS"));
        List<String> rolloutPlan = bullets(sections.get("ROLLOUT_PLAN"));
        List<String> rollbackPlan = bullets(sections.get("ROLLBACK_PLAN"));
        List<String> docsUpdates = bullets(sections.get("DOCS_UPDATES"));
        List<String> openQuestions = bullets(sections.get("OPEN_QUESTIONS"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));

        if (migrationSlices.isEmpty() && migrationOverview.isBlank() && compatibilityNotes.isEmpty()
                && testPlan.isEmpty() && rolloutPlan.isEmpty()) {
            // Non-compliant response: keep the whole (truncated) text usable in the overview.
            migrationOverview = text == null ? "" : text.strip();
        }
        return new ParsedPlan(summary, migrationOverview, migrationSlices, compatibilityNotes,
                breakingRisks, dependencyChanges, configurationChanges, testPlan, observabilityChecks,
                rolloutPlan, rollbackPlan, docsUpdates, openQuestions, risks, safetyNotes,
                assumptions);
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

    private String buildSummary(GenerateMigrationPlanInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        return cap("Migration plan (" + input.migrationType().wireValue() + ", "
                + input.compatibilityMode().wireValue() + "): " + input.task());
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
     * Parses the MIGRATION_SLICES block into structured slices. Defensive: a new slice starts at an
     * {@code id:} or {@code title:} line (optionally bulleted) or a bare bullet; {@code changes} and
     * {@code verification} are split on commas/semicolons; unknown risk values fall back to MEDIUM;
     * a malformed entry never aborts parsing of the rest.
     */
    static List<MigrationSlice> parseSlices(List<String> lines) {
        List<MigrationSlice> slices = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return slices;
        }
        SliceBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || isFenceLine(line)) {
                continue;
            }
            boolean bullet = line.startsWith("-") || line.startsWith("*");
            String core = bullet ? line.substring(1).strip() : line;

            Matcher keyMatcher = SLICE_KEY_PATTERN.matcher(core);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1).toLowerCase();
                String value = keyMatcher.group(2) == null ? "" : keyMatcher.group(2).strip();
                if ((key.equals("id") || key.equals("title")) && current != null
                        && current.startsKey(key)) {
                    slices.add(current.build());
                    current = null;
                }
                if (current == null) {
                    current = new SliceBuilder();
                }
                current.set(key, value);
            } else if (bullet) {
                // Bare bullet (no key): treat as a standalone slice title.
                if (current != null && current.hasContent()) {
                    slices.add(current.build());
                }
                current = new SliceBuilder();
                current.set("title", core);
            } else if (current != null) {
                // Continuation line: append to the goal text.
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            slices.add(current.build());
        }
        return List.copyOf(slices);
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String part : value.split("[,;]")) {
            String item = part.strip();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private static String normalizeRisk(String raw) {
        if (raw == null || raw.isBlank()) {
            return MigrationSlice.DEFAULT_RISK;
        }
        String norm = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_RISKS.contains(norm) ? norm : MigrationSlice.DEFAULT_RISK;
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

    record ParsedPlan(
            String summary,
            String migrationOverview,
            List<MigrationSlice> migrationSlices,
            List<String> compatibilityNotes,
            List<String> breakingRisks,
            List<String> dependencyChanges,
            List<String> configurationChanges,
            List<String> testPlan,
            List<String> observabilityChecks,
            List<String> rolloutPlan,
            List<String> rollbackPlan,
            List<String> docsUpdates,
            List<String> openQuestions,
            List<String> risks,
            List<String> safetyNotes,
            List<String> assumptions) {
    }

    private static final class SliceBuilder {
        private String id;
        private String title;
        private String goal;
        private String changes;
        private String verification;
        private String risk;
        private String rollback;

        void set(String key, String value) {
            switch (key) {
                case "id" -> id = value;
                case "title" -> title = value;
                case "goal" -> goal = value;
                case "changes" -> changes = value;
                case "verification" -> verification = value;
                case "risk" -> risk = value;
                case "rollback" -> rollback = value;
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        /** Whether the given starting key (id/title) already has content, signaling a new slice. */
        boolean startsKey(String key) {
            if (key.equals("id")) {
                return isNonBlank(id);
            }
            // A new title starts a slice only if a title is already set (id may precede title).
            return isNonBlank(title);
        }

        void appendContinuation(String text) {
            goal = append(goal, text);
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return isNonBlank(id) || isNonBlank(title) || isNonBlank(goal) || isNonBlank(changes)
                    || isNonBlank(verification) || isNonBlank(risk) || isNonBlank(rollback);
        }

        MigrationSlice build() {
            String t = title != null ? title : (goal != null ? goal : "Migration slice");
            return new MigrationSlice(
                    id == null ? "" : id,
                    t,
                    goal == null ? "" : goal,
                    splitList(changes),
                    splitList(verification),
                    normalizeRisk(risk),
                    rollback == null ? "" : rollback);
        }
    }
}
