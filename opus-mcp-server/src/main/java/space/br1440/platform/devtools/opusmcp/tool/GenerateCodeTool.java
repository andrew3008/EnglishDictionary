package space.br1440.platform.devtools.opusmcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.ModelRegistry;
import space.br1440.platform.devtools.opusmcp.model.OpusClient;
import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.model.OpusRequest;
import space.br1440.platform.devtools.opusmcp.model.OpusResponse;
import space.br1440.platform.devtools.opusmcp.prompt.PromptBuilder;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.audit.AuditRecord;
import space.br1440.platform.devtools.opusmcp.audit.ProviderAuditSupport;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.model.AnthropicHttpOpusClient;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.OutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2 read-only MCP tool: {@code generate_code_with_opus}.
 *
 * <p>Accepts explicit Cursor-provided task/context/constraints, delegates to the external Opus model,
 * and returns a structured proposal. Never reads/writes files or executes commands.
 */
public final class GenerateCodeTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateCodeTool.class);

    public static final String TOOL_NAME = "generate_code_with_opus";

    public static final String DESCRIPTION =
            "Read-only proposal generation tool. Does not read files, write files, execute commands, "
                    + "or apply patches. Use only for non-trivial code generation, refactoring proposals, "
                    + "test generation planning, or architecture-sensitive implementation planning. "
                    + "Cursor/user must review and apply results.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "language", "outputFormat", "riskLevel"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What to generate or propose."
                },
                "language": {
                  "type": "string",
                  "enum": ["java", "go", "kotlin", "sql", "mdx", "gradle", "other"]
                },
                "context": {
                  "type": "string",
                  "description": "Optional minimal relevant context explicitly provided by Cursor."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit constraints."
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["unified_diff", "full_file", "code_block", "implementation_plan", "review"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?im)^(?:ASSUMPTIONS|RISKS|SAFETY_NOTES|TESTS_TO_RUN)\\s*:\\s*$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final PromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public GenerateCodeTool(
            AppConfig config,
            OpusClient opusClient,
            PromptBuilder promptBuilder,
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

    GenerateCodeTool(
            AppConfig config,
            OpusClient opusClient,
            PromptBuilder promptBuilder,
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

    public GenerateCodeOutput handle(Map<String, Object> arguments) {
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
        Optional<GenerateCodeInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, language, outputFormat, and riskLevel are required",
                    requestId,
                    model));
        }

        GenerateCodeInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.context() + input.constraints());
        audit.language(input.language().wireValue())
                .outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
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
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    GenerateCodeStatus.BUDGET_EXCEEDED,
                    "Daily budget exceeded: " + budgetDecision.reason(),
                    requestId,
                    model));
        }

        // 9-13. Build prompt, call model (with retry), parse, update budget, audit.
        try {
            String systemPrompt = promptBuilder.buildSystemPrompt(input);
            String userPrompt = promptBuilder.buildUserPrompt(input);
            OpusRequest request = new OpusRequest(model, config.maxTokens(), systemPrompt, userPrompt);
            OpusResponse response = opusClient.generate(request);
            ProviderAuditSupport.applySuccess(audit, response);

            LimitsGuard.TruncationResult truncated = limitsGuard.truncateOutput(response.text());
            ParsedSections sections = parseSections(truncated.value());
            String summary = buildSummary(input, truncated.value());
            String resultBody = extractResultBody(truncated.value());

            budgetTracker.record(inputChars,
                    response.inputTokenEstimate(), response.outputTokenEstimate());

            audit.estimatedInputTokens(response.inputTokenEstimate())
                    .estimatedOutputTokens(response.outputTokenEstimate())
                    .estimatedCost(budgetTracker.snapshot().estimatedCost())
                    .httpStatusCategory("2xx");

            return finish(audit, startNanos, new GenerateCodeOutput(
                    GenerateCodeStatus.OK,
                    summary,
                    resultBody,
                    sections.risks(),
                    sections.safetyNotes(),
                    sections.assumptions(),
                    sections.testsToRun(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus call failed requestId={} reason={}", requestId, e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : mapExceptionStatus(e);
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, GenerateCodeOutput.ofStatus(
                    status,
                    errorMapper.safeMessageForException(e),
                    requestId,
                    model));
        }
    }

    private GenerateCodeOutput finish(
            AuditRecord.Builder audit, long startNanos, GenerateCodeOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(GenerateCodeInput input) {
        return (long) input.task().length() + input.context().length() + input.constraints().length();
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

    public String handleAsJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(toPayload(handle(arguments)));
        } catch (JsonProcessingException e) {
            return "{\"status\":\"MODEL_ERROR\",\"summary\":\"Failed to serialize tool output\",\"result\":\"\"}";
        }
    }

    private GenerateCodeStatus mapExceptionStatus(OpusClientException exception) {
        if (exception.reason() == OpusClientException.Reason.HTTP_ERROR && exception.httpStatus() == 429) {
            return GenerateCodeStatus.BUDGET_EXCEEDED;
        }
        return GenerateCodeStatus.MODEL_ERROR;
    }

    private Optional<GenerateCodeInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        if (task == null || task.isBlank()) {
            return Optional.empty();
        }
        Optional<CodeLanguage> language = CodeLanguage.fromWire(stringValue(arguments.get("language")));
        Optional<OutputFormat> outputFormat = OutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        if (language.isEmpty() || outputFormat.isEmpty() || riskLevel.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GenerateCodeInput(
                task.trim(),
                language.get(),
                nullToEmpty(stringValue(arguments.get("context"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                outputFormat.get(),
                riskLevel.get()));
    }

    private Optional<String> findDenyViolation(GenerateCodeInput input) {
        for (String field : List.of(input.task(), input.context(), input.constraints())) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(GenerateCodeInput input) {
        for (String field : List.of(input.task(), input.context(), input.constraints())) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(GenerateCodeInput input) {
        LimitsGuard.OptionalLimit contextLimit = limitsGuard.checkContextSize(input.context());
        if (contextLimit.exceeded()) {
            return Optional.of(contextLimit.message());
        }
        LimitsGuard.OptionalLimit constraintsLimit = limitsGuard.checkConstraintsSize(input.constraints());
        if (constraintsLimit.exceeded()) {
            return Optional.of(constraintsLimit.message());
        }
        return Optional.empty();
    }

    private static final int SUMMARY_MAX_LENGTH = 200;

    private static final Pattern EXPLICIT_SUMMARY_PATTERN = Pattern.compile(
            "(?im)^\\s*#{0,3}\\s*SUMMARY\\s*:?\\s*(.*)$");

    private static final Pattern SECTION_MARKER_PATTERN = Pattern.compile(
            "(?i)^#{0,3}\\s*(SUMMARY|RESULT|ASSUMPTIONS|RISKS|SAFETY_NOTES|TESTS_TO_RUN)\\s*:?\\s*$");

    /**
     * Matches any known structured-section header line, allowing optional markdown heading markers,
     * an optional inline value after the colon, and both underscore and space spellings
     * (e.g. {@code SAFETY_NOTES:} or {@code SAFETY NOTES:}). Group 1 = normalized-ish name,
     * group 2 = optional inline remainder.
     */
    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|RESULT|ASSUMPTIONS|RISKS|SAFETY[_ ]NOTES|TESTS[_ ]TO[_ ]RUN)\\s*:\\s*(.*)$");

    /**
     * Builds a human-readable summary. Never returns a markdown code fence, raw code, raw JSON, or
     * an empty string. Order: explicit SUMMARY section, then first meaningful line, then a
     * task/language fallback.
     */
    String buildSummary(GenerateCodeInput input, String result) {
        Optional<String> explicit = extractExplicitSummary(result);
        if (explicit.isPresent()) {
            return cap(explicit.get());
        }
        // If the output is essentially a fenced code block (rule 3), derive the summary from the
        // task/language rather than from the code itself.
        if (!startsWithCodeFence(result)) {
            Optional<String> meaningful = firstMeaningfulLine(result);
            if (meaningful.isPresent()) {
                return cap(meaningful.get());
            }
        }
        return fallbackSummary(input);
    }

    private Optional<String> extractExplicitSummary(String result) {
        if (result == null || result.isBlank()) {
            return Optional.empty();
        }
        String[] lines = result.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = EXPLICIT_SUMMARY_PATTERN.matcher(lines[i]);
            if (matcher.matches()) {
                String inline = matcher.group(1) == null ? "" : matcher.group(1).trim();
                if (!inline.isEmpty()) {
                    return Optional.of(inline);
                }
                // Summary value is on the following non-empty, meaningful line.
                for (int j = i + 1; j < lines.length; j++) {
                    String candidate = sanitizeLine(lines[j]);
                    if (candidate != null) {
                        return Optional.of(candidate);
                    }
                    if (!lines[j].isBlank()) {
                        break;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean startsWithCodeFence(String result) {
        if (result == null) {
            return false;
        }
        for (String line : result.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            return trimmed.startsWith("```") || trimmed.startsWith("~~~");
        }
        return false;
    }

    private Optional<String> firstMeaningfulLine(String result) {
        if (result == null || result.isBlank()) {
            return Optional.empty();
        }
        for (String line : result.split("\\R")) {
            String candidate = sanitizeLine(line);
            if (candidate != null) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Returns a cleaned line if it is a meaningful summary candidate, otherwise {@code null}. */
    private String sanitizeLine(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty()) {
            return null;
        }
        if (line.startsWith("```") || line.startsWith("~~~")) {
            return null;
        }
        if (SECTION_MARKER_PATTERN.matcher(line).matches()) {
            return null;
        }
        // Strip leading markdown bullets / heading markers for readability.
        String stripped = line.replaceFirst("^[-*#>\\s]+", "").trim();
        if (stripped.isEmpty()) {
            return null;
        }
        // Reject lines that are only JSON/markup punctuation.
        if (stripped.matches("[{}\\[\\](),;:\"']+")) {
            return null;
        }
        return stripped;
    }

    private String fallbackSummary(GenerateCodeInput input) {
        if (input == null) {
            return "Generated code proposal for the requested task.";
        }
        String languageLabel = capitalize(input.language().wireValue());
        String task = input.task() == null ? "" : input.task().trim();
        if (task.isEmpty()) {
            return "Generated " + languageLabel + " proposal for the requested task.";
        }
        return cap("Generated " + languageLabel + " code for: " + task);
    }

    private static String cap(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > SUMMARY_MAX_LENGTH) {
            return trimmed.substring(0, SUMMARY_MAX_LENGTH).trim() + "...";
        }
        return trimmed;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Phase 3.1: returns only the body of the {@code RESULT:} section, so the {@code result} field
     * never duplicates {@code summary}/{@code assumptions}/{@code risks}/{@code safetyNotes}/
     * {@code testsToRun} which are surfaced as separate fields.
     *
     * <p>The RESULT body runs from just after the {@code RESULT:} header until the next known
     * section header. Fenced code blocks are preserved verbatim, and header-looking lines inside a
     * fence are not treated as boundaries. Only outer whitespace is trimmed. If no {@code RESULT:}
     * section exists, or its body is empty, the original text is returned unchanged (Phase 2.1
     * fallback), so plain code-block responses keep their content.
     */
    static String extractResultBody(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String[] lines = text.split("\\R", -1);

        int resultHeaderIndex = -1;
        String inlineRemainder = "";
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            if (isFenceLine(lines[i])) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher matcher = SECTION_HEADER_LINE_PATTERN.matcher(lines[i]);
            if (matcher.matches() && normalizeSection(matcher.group(1)).equals("RESULT")) {
                resultHeaderIndex = i;
                inlineRemainder = matcher.group(2) == null ? "" : matcher.group(2).trim();
                break;
            }
        }

        if (resultHeaderIndex < 0) {
            return text.strip();
        }

        StringBuilder body = new StringBuilder();
        if (!inlineRemainder.isEmpty()) {
            body.append(inlineRemainder).append('\n');
        }
        boolean fence = false;
        for (int j = resultHeaderIndex + 1; j < lines.length; j++) {
            String line = lines[j];
            if (isFenceLine(line)) {
                fence = !fence;
                body.append(line).append('\n');
                continue;
            }
            if (!fence && SECTION_HEADER_LINE_PATTERN.matcher(line).matches()) {
                break;
            }
            body.append(line).append('\n');
        }

        String result = body.toString().strip();
        return result.isEmpty() ? text.strip() : result;
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

    static ParsedSections parseSections(String text) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("ASSUMPTIONS", new ArrayList<>());
        sections.put("RISKS", new ArrayList<>());
        sections.put("SAFETY_NOTES", new ArrayList<>());
        sections.put("TESTS_TO_RUN", new ArrayList<>());

        if (text == null || text.isBlank()) {
            return new ParsedSections(List.of(), List.of(), List.of(), List.of());
        }

        String current = null;
        for (String line : text.split("\\R")) {
            Matcher matcher = SECTION_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                current = line.trim().replace(":", "").toUpperCase();
                continue;
            }
            if (current != null && sections.containsKey(current)) {
                String item = line.trim();
                if (!item.isEmpty()) {
                    if (item.startsWith("-")) {
                        item = item.substring(1).trim();
                    }
                    if (!item.isEmpty()) {
                        sections.get(current).add(item);
                    }
                }
            }
        }
        return new ParsedSections(
                List.copyOf(sections.get("RISKS")),
                List.copyOf(sections.get("SAFETY_NOTES")),
                List.copyOf(sections.get("ASSUMPTIONS")),
                List.copyOf(sections.get("TESTS_TO_RUN")));
    }

    private Map<String, Object> toPayload(GenerateCodeOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("result", output.result());
        payload.put("risks", output.risks());
        payload.put("safetyNotes", output.safetyNotes());
        payload.put("assumptions", output.assumptions());
        payload.put("testsToRun", output.testsToRun());
        payload.put("truncated", output.truncated());
        payload.put("inputTokenEstimate", output.inputTokenEstimate());
        payload.put("outputTokenEstimate", output.outputTokenEstimate());
        payload.put("model", output.model());
        payload.put("requestId", output.requestId());
        return payload;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record ParsedSections(
            List<String> risks,
            List<String> safetyNotes,
            List<String> assumptions,
            List<String> testsToRun) {
    }
}
