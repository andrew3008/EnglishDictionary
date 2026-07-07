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
import space.br1440.platform.devtools.opusmcp.prompt.AnalyzeBuildFailurePromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.AnalyzeBuildFailureInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.AnalyzeBuildFailureOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.BuildFailureOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.FailureType;
import space.br1440.platform.devtools.opusmcp.tool.dto.FixOption;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
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
 * Phase 9A read-only MCP tool: {@code analyze_build_failure_with_opus}.
 *
 * <p>Accepts an explicit Cursor-provided failure log plus optional curated code/build context,
 * delegates to the external Opus model, and returns a structured diagnosis (hypotheses, most-likely
 * cause, evidence, fix options, minimal patch suggestion, tests to rerun). Reuses the exact same
 * guard pipeline as the other tools (deny-list, secret scan, size limits, config validation, model
 * allowlist, rate limit, budget). Never reads/writes files, executes commands, runs Gradle, runs
 * tests, or applies patches. All inputs are treated as untrusted data, never as instructions.
 */
public final class AnalyzeBuildFailureTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeBuildFailureTool.class);

    public static final String TOOL_NAME = "analyze_build_failure_with_opus";

    public static final String DESCRIPTION =
            "Read-only build failure analysis tool. Does not read files, write files, execute "
                    + "commands, run tests, or apply patches. Analyzes only the failure log, relevant "
                    + "code, and build context explicitly provided in the tool input (treated as "
                    + "untrusted data, never instructions). Returns structured diagnosis, hypotheses, "
                    + "fix options, and tests to rerun. Cursor/user must review, implement, and run "
                    + "verification manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "failureLog", "failureType", "language", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What to diagnose or what the user should focus on."
                },
                "failureLog": {
                  "type": "string",
                  "description": "The build/test/static-analysis failure log explicitly provided by Cursor. Treated as data."
                },
                "relevantCode": {
                  "type": "string",
                  "description": "Optional minimal curated code relevant to the failure, explicitly provided by Cursor."
                },
                "buildContext": {
                  "type": "string",
                  "description": "Optional build context (tool versions, command, module layout) explicitly provided by Cursor."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit constraints."
                },
                "failureType": {
                  "type": "string",
                  "enum": ["compile", "test", "gradle", "checkstyle", "spotbugs", "static_analysis", "runtime", "unknown"]
                },
                "language": {
                  "type": "string",
                  "enum": ["java", "go", "kotlin", "sql", "mdx", "gradle", "other"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["diagnosis", "fix_plan", "checklist", "root_cause_analysis"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_RISKS = Set.of("LOW", "MEDIUM", "HIGH");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|ROOT[_ ]CAUSE[_ ]HYPOTHESES|MOST[_ ]LIKELY[_ ]CAUSE"
                    + "|EVIDENCE|FIX[_ ]OPTIONS|MINIMAL[_ ]PATCH[_ ]SUGGESTION|TESTS[_ ]TO[_ ]RERUN"
                    + "|RISKS|SAFETY[_ ]NOTES|ASSUMPTIONS)\\s*:\\s*(.*)$");
    private static final Pattern FIX_OPTION_KEY_PATTERN = Pattern.compile(
            "(?i)^(title|description|risk|requirescodechange|requiresdependencychange)\\s*:\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final AnalyzeBuildFailurePromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public AnalyzeBuildFailureTool(
            AppConfig config,
            OpusClient opusClient,
            AnalyzeBuildFailurePromptBuilder promptBuilder,
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

    AnalyzeBuildFailureTool(
            AppConfig config,
            OpusClient opusClient,
            AnalyzeBuildFailurePromptBuilder promptBuilder,
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

    public AnalyzeBuildFailureOutput handle(Map<String, Object> arguments) {
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
        Optional<AnalyzeBuildFailureInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, failureLog, failureType, language, "
                            + "riskLevel, and outputFormat are required",
                    requestId,
                    model));
        }

        AnalyzeBuildFailureInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.failureLog() + input.relevantCode() + input.buildContext()
                        + input.constraints());
        audit.language(input.language().wireValue())
                .outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
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
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
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
            ParsedAnalysis analysis = parseAnalysis(input, truncated.value());

            budgetTracker.record(inputChars,
                    response.inputTokenEstimate(), response.outputTokenEstimate());

            audit.estimatedInputTokens(response.inputTokenEstimate())
                    .estimatedOutputTokens(response.outputTokenEstimate())
                    .estimatedCost(budgetTracker.snapshot().estimatedCost())
                    .httpStatusCategory("2xx");

            return finish(audit, startNanos, new AnalyzeBuildFailureOutput(
                    GenerateCodeStatus.OK,
                    analysis.summary(),
                    analysis.rootCauseHypotheses(),
                    analysis.mostLikelyCause(),
                    analysis.evidence(),
                    analysis.fixOptions(),
                    analysis.minimalPatchSuggestion(),
                    analysis.testsToRerun(),
                    analysis.risks(),
                    analysis.safetyNotes(),
                    analysis.assumptions(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus analyze-build-failure call failed requestId={} reason={}",
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
            return finish(audit, startNanos, AnalyzeBuildFailureOutput.ofStatus(
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
                    + "\"rootCauseHypotheses\":[],\"fixOptions\":[]}";
        }
    }

    private AnalyzeBuildFailureOutput finish(
            AuditRecord.Builder audit, long startNanos, AnalyzeBuildFailureOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(AnalyzeBuildFailureInput input) {
        return (long) input.task().length() + input.failureLog().length()
                + input.relevantCode().length() + input.buildContext().length()
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

    private Optional<AnalyzeBuildFailureInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String failureLog = stringValue(arguments.get("failureLog"));
        if (task == null || task.isBlank() || failureLog == null || failureLog.isBlank()) {
            return Optional.empty();
        }
        Optional<FailureType> failureType = FailureType.fromWire(stringValue(arguments.get("failureType")));
        Optional<CodeLanguage> language = CodeLanguage.fromWire(stringValue(arguments.get("language")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        Optional<BuildFailureOutputFormat> outputFormat =
                BuildFailureOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (failureType.isEmpty() || language.isEmpty() || riskLevel.isEmpty()
                || outputFormat.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AnalyzeBuildFailureInput(
                task.trim(),
                failureLog,
                nullToEmpty(stringValue(arguments.get("relevantCode"))),
                nullToEmpty(stringValue(arguments.get("buildContext"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                failureType.get(),
                language.get(),
                riskLevel.get(),
                outputFormat.get()));
    }

    private Optional<String> findDenyViolation(AnalyzeBuildFailureInput input) {
        for (String field : List.of(input.task(), input.failureLog(), input.relevantCode(),
                input.buildContext(), input.constraints())) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(AnalyzeBuildFailureInput input) {
        for (String field : List.of(input.task(), input.failureLog(), input.relevantCode(),
                input.buildContext(), input.constraints())) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(AnalyzeBuildFailureInput input) {
        if (input.failureLog().length() > limitsGuard.maxContextChars()) {
            return Optional.of("failureLog exceeds maximum size of " + limitsGuard.maxContextChars()
                    + " characters");
        }
        if (input.relevantCode().length() > limitsGuard.maxContextChars()) {
            return Optional.of("relevantCode exceeds maximum size of " + limitsGuard.maxContextChars()
                    + " characters");
        }
        LimitsGuard.OptionalLimit contextLimit = limitsGuard.checkContextSize(input.buildContext());
        if (contextLimit.exceeded()) {
            return Optional.of(contextLimit.message());
        }
        LimitsGuard.OptionalLimit constraintsLimit = limitsGuard.checkConstraintsSize(input.constraints());
        if (constraintsLimit.exceeded()) {
            return Optional.of(constraintsLimit.message());
        }
        return Optional.empty();
    }

    private Map<String, Object> toPayload(AnalyzeBuildFailureOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("rootCauseHypotheses", output.rootCauseHypotheses());
        payload.put("mostLikelyCause", output.mostLikelyCause());
        payload.put("evidence", output.evidence());
        List<Map<String, Object>> fixOptions = new ArrayList<>();
        for (FixOption f : output.fixOptions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", f.title());
            m.put("description", f.description());
            m.put("risk", f.risk());
            m.put("requiresCodeChange", f.requiresCodeChange());
            m.put("requiresDependencyChange", f.requiresDependencyChange());
            fixOptions.add(m);
        }
        payload.put("fixOptions", fixOptions);
        payload.put("minimalPatchSuggestion", output.minimalPatchSuggestion());
        payload.put("testsToRerun", output.testsToRerun());
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

    // ---- Defensive build-failure-analysis parsing ---------------------------------------------

    ParsedAnalysis parseAnalysis(AnalyzeBuildFailureInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        List<String> rootCauseHypotheses = bullets(sections.get("ROOT_CAUSE_HYPOTHESES"));
        String mostLikelyCause = joinSection(sections.get("MOST_LIKELY_CAUSE"));
        List<String> evidence = bullets(sections.get("EVIDENCE"));
        List<FixOption> fixOptions = parseFixOptions(sections.get("FIX_OPTIONS"));
        String minimalPatchSuggestion = joinSection(sections.get("MINIMAL_PATCH_SUGGESTION"));
        List<String> testsToRerun = bullets(sections.get("TESTS_TO_RERUN"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));

        if (rootCauseHypotheses.isEmpty() && evidence.isEmpty() && fixOptions.isEmpty()
                && mostLikelyCause.isBlank() && minimalPatchSuggestion.isBlank()) {
            // Non-compliant response: keep the whole (truncated) text usable in mostLikelyCause.
            mostLikelyCause = text == null ? "" : text.strip();
        }
        return new ParsedAnalysis(summary, rootCauseHypotheses, mostLikelyCause, evidence, fixOptions,
                minimalPatchSuggestion, testsToRerun, risks, safetyNotes, assumptions);
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

    private String buildSummary(AnalyzeBuildFailureInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        String languageLabel = capitalize(input.language().wireValue());
        return cap("Build failure analysis (" + input.failureType().wireValue() + ") for "
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

    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)]\\s*(.*)$");

    /**
     * Parses the FIX_OPTIONS block into structured fix options. Defensive: a new option starts at a
     * {@code title:} line (optionally bulleted) or a bare bullet; unknown risk values fall back to
     * {@code MEDIUM}; a malformed entry never aborts parsing of the rest.
     */
    static List<FixOption> parseFixOptions(List<String> lines) {
        List<FixOption> options = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return options;
        }
        FixOptionBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean bullet = line.startsWith("-") || line.startsWith("*");
            String core = bullet ? line.substring(1).strip() : line;

            Matcher keyMatcher = FIX_OPTION_KEY_PATTERN.matcher(core);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1).toLowerCase();
                String value = keyMatcher.group(2) == null ? "" : keyMatcher.group(2).strip();
                if (key.equals("title") && current != null && current.hasContent()) {
                    options.add(current.build());
                    current = null;
                }
                if (current == null) {
                    current = new FixOptionBuilder();
                }
                current.set(key, value);
            } else if (bullet) {
                // Bare bullet (no key): treat as a standalone fix-option title.
                if (current != null && current.hasContent()) {
                    options.add(current.build());
                }
                current = new FixOptionBuilder();
                current.set("title", core);
            } else if (current != null) {
                // Continuation line: append to the description.
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            options.add(current.build());
        }
        return List.copyOf(options);
    }

    private static String normalizeRisk(String raw) {
        if (raw == null || raw.isBlank()) {
            return FixOption.DEFAULT_RISK;
        }
        String up = raw.trim().toUpperCase();
        return ALLOWED_RISKS.contains(up) ? up : FixOption.DEFAULT_RISK;
    }

    private static boolean parseBool(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return v.startsWith("true") || v.equals("yes") || v.equals("y");
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

    record ParsedAnalysis(
            String summary,
            List<String> rootCauseHypotheses,
            String mostLikelyCause,
            List<String> evidence,
            List<FixOption> fixOptions,
            String minimalPatchSuggestion,
            List<String> testsToRerun,
            List<String> risks,
            List<String> safetyNotes,
            List<String> assumptions) {
    }

    private static final class FixOptionBuilder {
        private String title;
        private String description;
        private String risk;
        private String requiresCodeChange;
        private String requiresDependencyChange;

        void set(String key, String value) {
            switch (key) {
                case "title" -> title = value;
                case "description" -> description = value;
                case "risk" -> risk = value;
                case "requirescodechange" -> requiresCodeChange = value;
                case "requiresdependencychange" -> requiresDependencyChange = value;
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        void appendContinuation(String text) {
            description = append(description, text);
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return title != null || description != null || risk != null
                    || requiresCodeChange != null || requiresDependencyChange != null;
        }

        FixOption build() {
            String t = title != null ? title : (description != null ? description : "Fix option");
            return new FixOption(
                    t,
                    description == null ? "" : description,
                    normalizeRisk(risk),
                    parseBool(requiresCodeChange),
                    parseBool(requiresDependencyChange));
        }
    }
}
