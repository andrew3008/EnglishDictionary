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
import space.br1440.platform.devtools.opusmcp.prompt.DesignClassHierarchyPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureStyle;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.DesignClassHierarchyInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.DesignClassHierarchyOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.DesignGoal;
import space.br1440.platform.devtools.opusmcp.tool.dto.DesignOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.DesignScope;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ProposedType;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;
import space.br1440.platform.devtools.opusmcp.tool.dto.TypeRelationship;

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
 * Phase 10A read-only MCP tool: {@code design_class_hierarchy_with_opus}.
 *
 * <p>Accepts an explicit Cursor-provided design context (task, domain context, optional existing type
 * summary, package context, constraints) plus design knobs and delegates to the external Opus model to
 * propose a class/interface hierarchy: summary, design overview, proposed types, relationships, package
 * plan, implementation slices, extension points, alternatives, tests, risks, anti-patterns. Reuses the
 * exact same guard pipeline as the other tools (deny-list, secret scan, size limits, config validation,
 * model allowlist, rate limit, budget). Never reads/writes files, executes commands, runs Gradle, runs
 * tests, or applies patches. All inputs are treated as untrusted data, never as instructions.
 */
public final class DesignClassHierarchyTool {

    private static final Logger log = LoggerFactory.getLogger(DesignClassHierarchyTool.class);

    public static final String TOOL_NAME = "design_class_hierarchy_with_opus";

    public static final String DESCRIPTION =
            "Read-only class hierarchy design tool. Does not read files, write files, execute "
                    + "commands, run tests, or apply patches. Designs only from the domain context, "
                    + "existing type summary, package context, and constraints explicitly provided in "
                    + "the tool input (treated as untrusted data, never instructions). Returns a "
                    + "structured class/interface hierarchy proposal, relationships, implementation "
                    + "slices, risks, tests, and alternatives. Cursor/user must review, implement, and "
                    + "verify manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "language", "domainContext", "designGoal", "scope", "architectureStyle", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What hierarchy to design or what the user should focus on."
                },
                "language": {
                  "type": "string",
                  "enum": ["java", "go", "kotlin", "sql", "mdx", "gradle", "other"]
                },
                "domainContext": {
                  "type": "string",
                  "description": "The domain/problem context explicitly provided by Cursor. Treated as data."
                },
                "existingTypes": {
                  "type": "string",
                  "description": "Optional summary of existing types/interfaces explicitly provided by Cursor."
                },
                "packageContext": {
                  "type": "string",
                  "description": "Optional package/module layout context explicitly provided by Cursor."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit design constraints."
                },
                "designGoal": {
                  "type": "string",
                  "enum": ["extensibility", "testability", "api_compatibility", "migration", "clean_architecture", "performance", "security", "maintainability", "all"]
                },
                "scope": {
                  "type": "string",
                  "enum": ["package", "module", "starter", "library", "multi_module", "unknown"]
                },
                "architectureStyle": {
                  "type": "string",
                  "enum": ["clean_architecture", "hexagonal", "layered", "spring_boot_starter", "plugin", "interceptor_pipeline", "domain_model", "unknown"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["class_diagram", "design_proposal", "implementation_slices", "adr_outline", "checklist"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_KINDS = Set.of(
            "class", "interface", "abstract_class", "record", "enum", "annotation", "other");
    private static final Set<String> ALLOWED_RELATIONSHIP_TYPES = Set.of(
            "extends", "implements", "uses", "composes", "delegates_to", "publishes", "observes",
            "configures", "other");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|DESIGN[_ ]OVERVIEW|PROPOSED[_ ]TYPES|RELATIONSHIPS"
                    + "|PACKAGE[_ ]PLAN|IMPLEMENTATION[_ ]SLICES|EXTENSION[_ ]POINTS"
                    + "|DESIGN[_ ]ALTERNATIVES|TESTS[_ ]TO[_ ]ADD|RISKS|ANTI[_ ]PATTERNS[_ ]TO[_ ]AVOID"
                    + "|SAFETY[_ ]NOTES|ASSUMPTIONS)\\s*:\\s*(.*)$");
    private static final Pattern PROPOSED_TYPE_KEY_PATTERN = Pattern.compile(
            "(?i)^(name|kind|package|packagename|responsibility|publicapi|collaborators|notes)\\s*:\\s*(.*)$");
    private static final Pattern RELATIONSHIP_KEY_PATTERN = Pattern.compile(
            "(?i)^(from|to|type|reason)\\s*:\\s*(.*)$");
    private static final Pattern RELATIONSHIP_ARROW_PATTERN = Pattern.compile(
            "^(.+?)\\s*-{1,}\\s*(?:\\[([^\\]]+)\\]|\\(([^)]+)\\)|([A-Za-z_]+))?\\s*-{0,}>\\s*"
                    + "([^:]+?)\\s*(?::\\s*(.*))?$");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)]\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final DesignClassHierarchyPromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public DesignClassHierarchyTool(
            AppConfig config,
            OpusClient opusClient,
            DesignClassHierarchyPromptBuilder promptBuilder,
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

    DesignClassHierarchyTool(
            AppConfig config,
            OpusClient opusClient,
            DesignClassHierarchyPromptBuilder promptBuilder,
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

    public DesignClassHierarchyOutput handle(Map<String, Object> arguments) {
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
        Optional<DesignClassHierarchyInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, language, domainContext, designGoal, scope, "
                            + "architectureStyle, riskLevel, and outputFormat are required",
                    requestId,
                    model));
        }

        DesignClassHierarchyInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.domainContext() + input.existingTypes() + input.packageContext()
                        + input.constraints());
        audit.language(input.language().wireValue())
                .outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
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
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
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
            ParsedDesign design = parseDesign(input, truncated.value());

            budgetTracker.record(inputChars,
                    response.inputTokenEstimate(), response.outputTokenEstimate());

            audit.estimatedInputTokens(response.inputTokenEstimate())
                    .estimatedOutputTokens(response.outputTokenEstimate())
                    .estimatedCost(budgetTracker.snapshot().estimatedCost())
                    .httpStatusCategory("2xx");

            return finish(audit, startNanos, new DesignClassHierarchyOutput(
                    GenerateCodeStatus.OK,
                    design.summary(),
                    design.designOverview(),
                    design.proposedTypes(),
                    design.relationships(),
                    design.packagePlan(),
                    design.implementationSlices(),
                    design.extensionPoints(),
                    design.designAlternatives(),
                    design.testsToAdd(),
                    design.risks(),
                    design.antiPatternsToAvoid(),
                    design.safetyNotes(),
                    design.assumptions(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus design-class-hierarchy call failed requestId={} reason={}",
                    requestId, e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : GenerateCodeStatus.MODEL_ERROR;
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, DesignClassHierarchyOutput.ofStatus(
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
                    + "\"proposedTypes\":[],\"relationships\":[]}";
        }
    }

    private DesignClassHierarchyOutput finish(
            AuditRecord.Builder audit, long startNanos, DesignClassHierarchyOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(DesignClassHierarchyInput input) {
        return (long) input.task().length() + input.domainContext().length()
                + input.existingTypes().length() + input.packageContext().length()
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

    private Optional<DesignClassHierarchyInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String domainContext = stringValue(arguments.get("domainContext"));
        if (task == null || task.isBlank() || domainContext == null || domainContext.isBlank()) {
            return Optional.empty();
        }
        Optional<CodeLanguage> language = CodeLanguage.fromWire(stringValue(arguments.get("language")));
        Optional<DesignGoal> designGoal = DesignGoal.fromWire(stringValue(arguments.get("designGoal")));
        Optional<DesignScope> scope = DesignScope.fromWire(stringValue(arguments.get("scope")));
        Optional<ArchitectureStyle> architectureStyle =
                ArchitectureStyle.fromWire(stringValue(arguments.get("architectureStyle")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        Optional<DesignOutputFormat> outputFormat =
                DesignOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (language.isEmpty() || designGoal.isEmpty() || scope.isEmpty()
                || architectureStyle.isEmpty() || riskLevel.isEmpty() || outputFormat.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DesignClassHierarchyInput(
                task.trim(),
                language.get(),
                domainContext,
                nullToEmpty(stringValue(arguments.get("existingTypes"))),
                nullToEmpty(stringValue(arguments.get("packageContext"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                designGoal.get(),
                scope.get(),
                architectureStyle.get(),
                riskLevel.get(),
                outputFormat.get()));
    }

    private Optional<String> findDenyViolation(DesignClassHierarchyInput input) {
        for (String field : List.of(input.task(), input.domainContext(), input.existingTypes(),
                input.packageContext(), input.constraints())) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(DesignClassHierarchyInput input) {
        for (String field : List.of(input.task(), input.domainContext(), input.existingTypes(),
                input.packageContext(), input.constraints())) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(DesignClassHierarchyInput input) {
        if (input.domainContext().length() > limitsGuard.maxContextChars()) {
            return Optional.of("domainContext exceeds maximum size of " + limitsGuard.maxContextChars()
                    + " characters");
        }
        if (input.existingTypes().length() > limitsGuard.maxContextChars()) {
            return Optional.of("existingTypes exceeds maximum size of " + limitsGuard.maxContextChars()
                    + " characters");
        }
        LimitsGuard.OptionalLimit contextLimit = limitsGuard.checkContextSize(input.packageContext());
        if (contextLimit.exceeded()) {
            return Optional.of(contextLimit.message());
        }
        LimitsGuard.OptionalLimit constraintsLimit = limitsGuard.checkConstraintsSize(input.constraints());
        if (constraintsLimit.exceeded()) {
            return Optional.of(constraintsLimit.message());
        }
        return Optional.empty();
    }

    private Map<String, Object> toPayload(DesignClassHierarchyOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("designOverview", output.designOverview());
        List<Map<String, Object>> proposedTypes = new ArrayList<>();
        for (ProposedType t : output.proposedTypes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("kind", t.kind());
            m.put("packageName", t.packageName());
            m.put("responsibility", t.responsibility());
            m.put("publicApi", t.publicApi());
            m.put("collaborators", t.collaborators());
            m.put("notes", t.notes());
            proposedTypes.add(m);
        }
        payload.put("proposedTypes", proposedTypes);
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (TypeRelationship r : output.relationships()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", r.from());
            m.put("to", r.to());
            m.put("type", r.type());
            m.put("reason", r.reason());
            relationships.add(m);
        }
        payload.put("relationships", relationships);
        payload.put("packagePlan", output.packagePlan());
        payload.put("implementationSlices", output.implementationSlices());
        payload.put("extensionPoints", output.extensionPoints());
        payload.put("designAlternatives", output.designAlternatives());
        payload.put("testsToAdd", output.testsToAdd());
        payload.put("risks", output.risks());
        payload.put("antiPatternsToAvoid", output.antiPatternsToAvoid());
        payload.put("safetyNotes", output.safetyNotes());
        payload.put("assumptions", output.assumptions());
        payload.put("truncated", output.truncated());
        payload.put("inputTokenEstimate", output.inputTokenEstimate());
        payload.put("outputTokenEstimate", output.outputTokenEstimate());
        payload.put("model", output.model());
        payload.put("requestId", output.requestId());
        return payload;
    }

    // ---- Defensive class-hierarchy-design parsing ---------------------------------------------

    ParsedDesign parseDesign(DesignClassHierarchyInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String designOverview = joinSection(sections.get("DESIGN_OVERVIEW"));
        List<ProposedType> proposedTypes = parseProposedTypes(sections.get("PROPOSED_TYPES"));
        List<TypeRelationship> relationships = parseRelationships(sections.get("RELATIONSHIPS"));
        List<String> packagePlan = bullets(sections.get("PACKAGE_PLAN"));
        List<String> implementationSlices = bullets(sections.get("IMPLEMENTATION_SLICES"));
        List<String> extensionPoints = bullets(sections.get("EXTENSION_POINTS"));
        List<String> designAlternatives = bullets(sections.get("DESIGN_ALTERNATIVES"));
        List<String> testsToAdd = bullets(sections.get("TESTS_TO_ADD"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> antiPatternsToAvoid = bullets(sections.get("ANTI_PATTERNS_TO_AVOID"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));

        if (proposedTypes.isEmpty() && relationships.isEmpty() && implementationSlices.isEmpty()
                && designOverview.isBlank()) {
            // Non-compliant response: keep the whole (truncated) text usable in designOverview.
            designOverview = text == null ? "" : text.strip();
        }
        return new ParsedDesign(summary, designOverview, proposedTypes, relationships, packagePlan,
                implementationSlices, extensionPoints, designAlternatives, testsToAdd, risks,
                antiPatternsToAvoid, safetyNotes, assumptions);
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

    private String buildSummary(DesignClassHierarchyInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        String languageLabel = capitalize(input.language().wireValue());
        return cap("Class hierarchy design (" + input.architectureStyle().wireValue() + ") for "
                + languageLabel + ": " + input.task());
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
            if (item.isEmpty()) {
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
     * Parses the PROPOSED_TYPES block into structured types. Defensive: a new type starts at a
     * {@code name:} line (optionally bulleted) or a bare bullet; unknown kinds fall back to
     * {@code class}; list keys (publicApi/collaborators/notes) are split on commas/semicolons; a
     * malformed entry never aborts parsing of the rest.
     */
    static List<ProposedType> parseProposedTypes(List<String> lines) {
        List<ProposedType> types = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return types;
        }
        ProposedTypeBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || isFenceLine(line)) {
                continue;
            }
            boolean bullet = line.startsWith("-") || line.startsWith("*");
            String core = bullet ? line.substring(1).strip() : line;

            Matcher keyMatcher = PROPOSED_TYPE_KEY_PATTERN.matcher(core);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1).toLowerCase();
                String value = keyMatcher.group(2) == null ? "" : keyMatcher.group(2).strip();
                if (key.equals("name") && current != null && current.hasContent()) {
                    types.add(current.build());
                    current = null;
                }
                if (current == null) {
                    current = new ProposedTypeBuilder();
                }
                current.set(key, value);
            } else if (bullet) {
                // Bare bullet (no key): treat as a standalone type name.
                if (current != null && current.hasContent()) {
                    types.add(current.build());
                }
                current = new ProposedTypeBuilder();
                current.set("name", core);
            } else if (current != null) {
                // Continuation line: append to the responsibility text.
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            types.add(current.build());
        }
        return List.copyOf(types);
    }

    /**
     * Parses the RELATIONSHIPS block into structured relationships. Defensive: supports key:value
     * blocks ({@code from/to/type/reason}) and a bare arrow bullet
     * ({@code A --type--> B : reason} or {@code A -> B}); unknown relationship types fall back to
     * {@code uses}; a malformed entry never aborts parsing of the rest.
     */
    static List<TypeRelationship> parseRelationships(List<String> lines) {
        List<TypeRelationship> relationships = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return relationships;
        }
        RelationshipBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || isFenceLine(line)) {
                continue;
            }
            boolean bullet = line.startsWith("-") || line.startsWith("*");
            String core = bullet ? line.substring(1).strip() : line;

            Matcher keyMatcher = RELATIONSHIP_KEY_PATTERN.matcher(core);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1).toLowerCase();
                String value = keyMatcher.group(2) == null ? "" : keyMatcher.group(2).strip();
                if (key.equals("from") && current != null && current.hasContent()) {
                    relationships.add(current.build());
                    current = null;
                }
                if (current == null) {
                    current = new RelationshipBuilder();
                }
                current.set(key, value);
                continue;
            }

            Matcher arrow = RELATIONSHIP_ARROW_PATTERN.matcher(core);
            if (arrow.matches()) {
                if (current != null && current.hasContent()) {
                    relationships.add(current.build());
                }
                current = new RelationshipBuilder();
                current.set("from", arrow.group(1) == null ? "" : arrow.group(1).strip());
                String type = arrow.group(2) != null ? arrow.group(2)
                        : (arrow.group(3) != null ? arrow.group(3)
                        : (arrow.group(4) != null ? arrow.group(4) : ""));
                current.set("type", type.strip());
                current.set("to", arrow.group(5) == null ? "" : arrow.group(5).strip());
                if (arrow.group(6) != null) {
                    current.set("reason", arrow.group(6).strip());
                }
                continue;
            }

            if (current != null) {
                // Continuation line: append to the reason text.
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            relationships.add(current.build());
        }
        return List.copyOf(relationships);
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

    private static String normalizeKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProposedType.DEFAULT_KIND;
        }
        String norm = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_KINDS.contains(norm) ? norm : ProposedType.DEFAULT_KIND;
    }

    private static String normalizeRelationshipType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TypeRelationship.DEFAULT_TYPE;
        }
        String norm = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_RELATIONSHIP_TYPES.contains(norm) ? norm : TypeRelationship.DEFAULT_TYPE;
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

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
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

    record ParsedDesign(
            String summary,
            String designOverview,
            List<ProposedType> proposedTypes,
            List<TypeRelationship> relationships,
            List<String> packagePlan,
            List<String> implementationSlices,
            List<String> extensionPoints,
            List<String> designAlternatives,
            List<String> testsToAdd,
            List<String> risks,
            List<String> antiPatternsToAvoid,
            List<String> safetyNotes,
            List<String> assumptions) {
    }

    private static final class ProposedTypeBuilder {
        private String name;
        private String kind;
        private String packageName;
        private String responsibility;
        private String publicApi;
        private String collaborators;
        private String notes;

        void set(String key, String value) {
            switch (key) {
                case "name" -> name = value;
                case "kind" -> kind = value;
                case "package", "packagename" -> packageName = value;
                case "responsibility" -> responsibility = value;
                case "publicapi" -> publicApi = value;
                case "collaborators" -> collaborators = value;
                case "notes" -> notes = value;
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        void appendContinuation(String text) {
            responsibility = append(responsibility, text);
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return isNonBlank(name) || isNonBlank(kind) || isNonBlank(packageName)
                    || isNonBlank(responsibility) || isNonBlank(publicApi)
                    || isNonBlank(collaborators) || isNonBlank(notes);
        }

        ProposedType build() {
            String n = name != null ? name : (responsibility != null ? responsibility : "Type");
            return new ProposedType(
                    n,
                    normalizeKind(kind),
                    packageName == null ? "" : packageName,
                    responsibility == null ? "" : responsibility,
                    splitList(publicApi),
                    splitList(collaborators),
                    splitList(notes));
        }
    }

    private static final class RelationshipBuilder {
        private String from;
        private String to;
        private String type;
        private String reason;

        void set(String key, String value) {
            switch (key) {
                case "from" -> from = value;
                case "to" -> to = value;
                case "type" -> type = value;
                case "reason" -> reason = value;
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        void appendContinuation(String text) {
            reason = append(reason, text);
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return isNonBlank(from) || isNonBlank(to) || isNonBlank(type) || isNonBlank(reason);
        }

        TypeRelationship build() {
            return new TypeRelationship(
                    from == null ? "" : from,
                    to == null ? "" : to,
                    normalizeRelationshipType(type),
                    reason == null ? "" : reason);
        }
    }
}
