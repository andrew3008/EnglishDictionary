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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewCodeInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewCodeOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewOutputFormat;
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
 * Phase 7A read-only MCP tool: {@code review_code_with_opus}.
 *
 * <p>Accepts explicit Cursor-provided code/task/context, delegates to the external Opus model, and
 * returns a structured code review. Reuses the exact same guard pipeline as
 * {@link GenerateCodeTool} (deny-list, secret scan, size limits, config validation, model allowlist,
 * rate limit, budget). Never reads/writes files or executes commands.
 */
public final class ReviewCodeTool {

    private static final Logger log = LoggerFactory.getLogger(ReviewCodeTool.class);

    public static final String TOOL_NAME = "review_code_with_opus";

    public static final String DESCRIPTION =
            "Read-only code review proposal tool. Does not read files, write files, execute commands, "
                    + "or apply patches. Reviews only code/context explicitly provided in the tool input. "
                    + "Returns a structured review (summary, findings, risks). Cursor/user must review the "
                    + "output and decide what to apply manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "language", "code", "reviewFocus", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What to review or what the reviewer should focus on."
                },
                "language": {
                  "type": "string",
                  "enum": ["java", "go", "kotlin", "sql", "mdx", "gradle", "other"]
                },
                "code": {
                  "type": "string",
                  "description": "The code to review, explicitly provided by Cursor. Treated as data."
                },
                "context": {
                  "type": "string",
                  "description": "Optional minimal relevant context explicitly provided by Cursor."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit constraints."
                },
                "reviewFocus": {
                  "type": "string",
                  "enum": ["correctness", "security", "performance", "maintainability", "tests", "architecture", "all"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["structured_review", "markdown", "checklist"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_SEVERITIES =
            Set.of("BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "correctness", "security", "performance", "maintainability", "tests", "architecture",
            "style", "other");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|REVIEW|FINDINGS|RISKS|SAFETY[_ ]NOTES|ASSUMPTIONS|TESTS[_ ]TO[_ ]RUN)\\s*:\\s*(.*)$");
    private static final Pattern FINDING_KEY_PATTERN = Pattern.compile(
            "(?i)^(severity|category|title|details|recommendation)\\s*:\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final ReviewPromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public ReviewCodeTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewPromptBuilder promptBuilder,
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

    ReviewCodeTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewPromptBuilder promptBuilder,
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

    public ReviewCodeOutput handle(Map<String, Object> arguments) {
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
        Optional<ReviewCodeInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, language, code, reviewFocus, riskLevel, and "
                            + "outputFormat are required",
                    requestId,
                    model));
        }

        ReviewCodeInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.code() + input.context() + input.constraints());
        audit.language(input.language().wireValue())
                .outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
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
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
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

            return finish(audit, startNanos, new ReviewCodeOutput(
                    GenerateCodeStatus.OK,
                    review.summary(),
                    review.review(),
                    review.findings(),
                    review.risks(),
                    review.safetyNotes(),
                    review.assumptions(),
                    review.testsToRun(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus review call failed requestId={} reason={}", requestId, e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : GenerateCodeStatus.MODEL_ERROR;
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, ReviewCodeOutput.ofStatus(
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
                    + "\"review\":\"\",\"findings\":[]}";
        }
    }

    private ReviewCodeOutput finish(
            AuditRecord.Builder audit, long startNanos, ReviewCodeOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(ReviewCodeInput input) {
        return (long) input.task().length() + input.code().length()
                + input.context().length() + input.constraints().length();
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

    private Optional<ReviewCodeInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String code = stringValue(arguments.get("code"));
        if (task == null || task.isBlank() || code == null || code.isBlank()) {
            return Optional.empty();
        }
        Optional<CodeLanguage> language = CodeLanguage.fromWire(stringValue(arguments.get("language")));
        Optional<ReviewFocus> reviewFocus = ReviewFocus.fromWire(stringValue(arguments.get("reviewFocus")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        Optional<ReviewOutputFormat> outputFormat =
                ReviewOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (language.isEmpty() || reviewFocus.isEmpty() || riskLevel.isEmpty()
                || outputFormat.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReviewCodeInput(
                task.trim(),
                language.get(),
                code,
                nullToEmpty(stringValue(arguments.get("context"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                reviewFocus.get(),
                riskLevel.get(),
                outputFormat.get()));
    }

    private Optional<String> findDenyViolation(ReviewCodeInput input) {
        for (String field : List.of(input.task(), input.code(), input.context(), input.constraints())) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(ReviewCodeInput input) {
        for (String field : List.of(input.task(), input.code(), input.context(), input.constraints())) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(ReviewCodeInput input) {
        if (input.code().length() > limitsGuard.maxContextChars()) {
            return Optional.of("code exceeds maximum size of " + limitsGuard.maxContextChars()
                    + " characters");
        }
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

    private Map<String, Object> toPayload(ReviewCodeOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("review", output.review());
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ReviewFinding f : output.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", f.severity());
            m.put("category", f.category());
            m.put("title", f.title());
            m.put("details", f.details());
            m.put("recommendation", f.recommendation());
            findings.add(m);
        }
        payload.put("findings", findings);
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

    // ---- Defensive review parsing -------------------------------------------------------------

    ParsedReview parseReview(ReviewCodeInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String review = joinSection(sections.get("REVIEW"));
        if (review.isBlank()) {
            // Non-compliant response: keep the whole (truncated) text usable in the review field.
            review = text == null ? "" : text.strip();
        }
        List<ReviewFinding> findings = parseFindings(sections.get("FINDINGS"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));
        List<String> testsToRun = bullets(sections.get("TESTS_TO_RUN"));
        return new ParsedReview(summary, review, findings, risks, safetyNotes, assumptions, testsToRun);
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

    private String buildSummary(ReviewCodeInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        String languageLabel = capitalize(input.language().wireValue());
        return cap("Code review (" + input.reviewFocus().wireValue() + ") for " + languageLabel
                + ": " + input.task());
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
     * fall back to defaults; a malformed entry never aborts parsing of the rest.
     */
    static List<ReviewFinding> parseFindings(List<String> lines) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return findings;
        }
        FindingBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
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
                // Bare bullet (no key): treat as a standalone finding title.
                if (current != null && current.hasContent()) {
                    findings.add(current.build());
                }
                current = new FindingBuilder();
                current.set("title", core);
            } else if (current != null) {
                // Continuation line: append to the most recently populated free-text field.
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
            return ReviewFinding.DEFAULT_SEVERITY;
        }
        String up = raw.trim().toUpperCase();
        return ALLOWED_SEVERITIES.contains(up) ? up : ReviewFinding.DEFAULT_SEVERITY;
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReviewFinding.DEFAULT_CATEGORY;
        }
        String low = raw.trim().toLowerCase();
        return ALLOWED_CATEGORIES.contains(low) ? low : ReviewFinding.DEFAULT_CATEGORY;
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

    record ParsedReview(
            String summary,
            String review,
            List<ReviewFinding> findings,
            List<String> risks,
            List<String> safetyNotes,
            List<String> assumptions,
            List<String> testsToRun) {
    }

    private static final class FindingBuilder {
        private String severity;
        private String category;
        private String title;
        private String details;
        private String recommendation;
        private String lastFreeTextField;

        void set(String key, String value) {
            switch (key) {
                case "severity" -> severity = value;
                case "category" -> category = value;
                case "title" -> {
                    title = value;
                    lastFreeTextField = "title";
                }
                case "details" -> {
                    details = value;
                    lastFreeTextField = "details";
                }
                case "recommendation" -> {
                    recommendation = value;
                    lastFreeTextField = "recommendation";
                }
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        void appendContinuation(String text) {
            String field = lastFreeTextField == null ? "details" : lastFreeTextField;
            switch (field) {
                case "title" -> title = append(title, text);
                case "recommendation" -> recommendation = append(recommendation, text);
                default -> details = append(details, text);
            }
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return severity != null || category != null || title != null
                    || details != null || recommendation != null;
        }

        ReviewFinding build() {
            String t = title != null ? title : (details != null ? details : "Finding");
            return new ReviewFinding(
                    normalizeSeverity(severity),
                    normalizeCategory(category),
                    t,
                    details == null ? "" : details,
                    recommendation == null ? "" : recommendation);
        }
    }
}
