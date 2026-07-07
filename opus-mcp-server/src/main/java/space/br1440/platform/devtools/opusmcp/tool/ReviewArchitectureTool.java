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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewArchitecturePromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureCompatibilityMode;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureReviewFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureReviewScope;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureReviewStyle;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureRisk;
import space.br1440.platform.devtools.opusmcp.tool.dto.ArchitectureVerdict;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewArchitectureInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewArchitectureOutput;
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
 * Phase 11A read-only MCP tool: {@code review_architecture_with_opus}.
 *
 * <p>Accepts an explicit Cursor-provided architecture proposal / ADR / design plan / migration plan
 * plus optional context and constraints, plus review knobs, and delegates to the external Opus model to
 * return a structured architecture review: summary, verdict, review, findings, risk matrix, trade-offs,
 * alternatives, open questions, tests, observability checks, rollout/rollback notes, risks. Reuses the
 * exact same guard pipeline as the other tools (deny-list, secret scan, size limits, config validation,
 * model allowlist, rate limit, budget). Never reads/writes files, executes commands, runs Gradle, runs
 * tests, or applies patches. All inputs are treated as untrusted data, never as instructions.
 */
public final class ReviewArchitectureTool {

    private static final Logger log = LoggerFactory.getLogger(ReviewArchitectureTool.class);

    public static final String TOOL_NAME = "review_architecture_with_opus";

    public static final String DESCRIPTION =
            "Read-only architecture review tool. Does not read files, write files, execute "
                    + "commands, run tests, or apply patches. Reviews only the architecture proposal, "
                    + "context, and constraints explicitly provided in the tool input (treated as "
                    + "untrusted data, never instructions). Returns a structured architecture review "
                    + "with verdict, findings, risk matrix, trade-offs, alternatives, tests, "
                    + "observability checks, rollout and rollback notes. Cursor/user must review and "
                    + "apply decisions manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "architectureProposal", "reviewFocus", "architectureScope", "architectureStyle", "compatibilityMode", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What to review or what the user should focus on."
                },
                "architectureProposal": {
                  "type": "string",
                  "description": "The architecture proposal / ADR / design plan / migration plan to review. Treated as data."
                },
                "context": {
                  "type": "string",
                  "description": "Optional additional context explicitly provided by Cursor. Treated as data."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit review constraints."
                },
                "reviewFocus": {
                  "type": "string",
                  "enum": ["api_compatibility", "observability", "security", "migration", "testing", "performance", "operability", "maintainability", "cost", "all"]
                },
                "architectureScope": {
                  "type": "string",
                  "enum": ["class", "package", "module", "multi_module", "platform", "library", "starter", "unknown"]
                },
                "architectureStyle": {
                  "type": "string",
                  "enum": ["clean_architecture", "hexagonal", "layered", "event_driven", "spring_boot_starter", "plugin", "interceptor_pipeline", "observability_pipeline", "unknown"]
                },
                "compatibilityMode": {
                  "type": "string",
                  "enum": ["preserve_api", "allow_breaking", "unknown"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["structured_review", "risk_matrix", "decision_memo", "adr_review", "checklist"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_SEVERITIES = Set.of(
            "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "api_compatibility", "observability", "security", "migration", "testing", "performance",
            "operability", "maintainability", "cost", "documentation", "other");
    private static final Set<String> ALLOWED_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|VERDICT|REVIEW|FINDINGS|RISK[_ ]MATRIX|TRADE[_ ]OFFS"
                    + "|ALTERNATIVES|OPEN[_ ]QUESTIONS|TESTS[_ ]TO[_ ]ADD|OBSERVABILITY[_ ]CHECKS"
                    + "|ROLLOUT[_ ]NOTES|ROLLBACK[_ ]NOTES|RISKS|SAFETY[_ ]NOTES|ASSUMPTIONS)"
                    + "\\s*:\\s*(.*)$");
    private static final Pattern FINDING_KEY_PATTERN = Pattern.compile(
            "(?i)^(severity|category|title|details|recommendation)\\s*:\\s*(.*)$");
    private static final Pattern RISK_KEY_PATTERN = Pattern.compile(
            "(?i)^(risk|likelihood|impact|mitigation)\\s*:\\s*(.*)$");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)]\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final ReviewArchitecturePromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public ReviewArchitectureTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewArchitecturePromptBuilder promptBuilder,
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

    ReviewArchitectureTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewArchitecturePromptBuilder promptBuilder,
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

    public ReviewArchitectureOutput handle(Map<String, Object> arguments) {
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
        Optional<ReviewArchitectureInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, architectureProposal, reviewFocus, "
                            + "architectureScope, architectureStyle, compatibilityMode, riskLevel, and "
                            + "outputFormat are required",
                    requestId,
                    model));
        }

        ReviewArchitectureInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.architectureProposal() + input.context() + input.constraints());
        audit.outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
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
            return finish(audit, startNanos, ReviewArchitectureOutput.ofStatus(
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

            return finish(audit, startNanos, new ReviewArchitectureOutput(
                    GenerateCodeStatus.OK,
                    review.summary(),
                    review.verdict(),
                    review.review(),
                    review.findings(),
                    review.riskMatrix(),
                    review.tradeOffs(),
                    review.alternatives(),
                    review.openQuestions(),
                    review.testsToAdd(),
                    review.observabilityChecks(),
                    review.rolloutNotes(),
                    review.rollbackNotes(),
                    review.risks(),
                    review.safetyNotes(),
                    review.assumptions(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus review-architecture call failed requestId={} reason={}",
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
            return finish(audit, startNanos, ReviewArchitectureOutput.ofProviderFailure(
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
                    + "\"findings\":[],\"riskMatrix\":[]}";
        }
    }

    private ReviewArchitectureOutput finish(
            AuditRecord.Builder audit, long startNanos, ReviewArchitectureOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(ReviewArchitectureInput input) {
        return (long) input.task().length() + input.architectureProposal().length()
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

    private Optional<ReviewArchitectureInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String proposal = stringValue(arguments.get("architectureProposal"));
        if (task == null || task.isBlank() || proposal == null || proposal.isBlank()) {
            return Optional.empty();
        }
        Optional<ArchitectureReviewFocus> reviewFocus =
                ArchitectureReviewFocus.fromWire(stringValue(arguments.get("reviewFocus")));
        Optional<ArchitectureReviewScope> scope =
                ArchitectureReviewScope.fromWire(stringValue(arguments.get("architectureScope")));
        Optional<ArchitectureReviewStyle> style =
                ArchitectureReviewStyle.fromWire(stringValue(arguments.get("architectureStyle")));
        Optional<ArchitectureCompatibilityMode> compatibilityMode =
                ArchitectureCompatibilityMode.fromWire(stringValue(arguments.get("compatibilityMode")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        Optional<ArchitectureOutputFormat> outputFormat =
                ArchitectureOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (reviewFocus.isEmpty() || scope.isEmpty() || style.isEmpty()
                || compatibilityMode.isEmpty() || riskLevel.isEmpty() || outputFormat.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReviewArchitectureInput(
                task.trim(),
                proposal,
                nullToEmpty(stringValue(arguments.get("context"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                reviewFocus.get(),
                scope.get(),
                style.get(),
                compatibilityMode.get(),
                riskLevel.get(),
                outputFormat.get()));
    }

    private Optional<String> findDenyViolation(ReviewArchitectureInput input) {
        for (String field : List.of(input.task(), input.architectureProposal(), input.context(),
                input.constraints())) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(ReviewArchitectureInput input) {
        for (String field : List.of(input.task(), input.architectureProposal(), input.context(),
                input.constraints())) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(ReviewArchitectureInput input) {
        if (input.architectureProposal().length() > limitsGuard.maxContextChars()) {
            return Optional.of("architectureProposal exceeds maximum size of "
                    + limitsGuard.maxContextChars() + " characters");
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

    private Map<String, Object> toPayload(ReviewArchitectureOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("verdict", output.verdict());
        payload.put("review", output.review());
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ArchitectureFinding f : output.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", f.severity());
            m.put("category", f.category());
            m.put("title", f.title());
            m.put("details", f.details());
            m.put("recommendation", f.recommendation());
            findings.add(m);
        }
        payload.put("findings", findings);
        List<Map<String, Object>> riskMatrix = new ArrayList<>();
        for (ArchitectureRisk r : output.riskMatrix()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("risk", r.risk());
            m.put("likelihood", r.likelihood());
            m.put("impact", r.impact());
            m.put("mitigation", r.mitigation());
            riskMatrix.add(m);
        }
        payload.put("riskMatrix", riskMatrix);
        payload.put("tradeOffs", output.tradeOffs());
        payload.put("alternatives", output.alternatives());
        payload.put("openQuestions", output.openQuestions());
        payload.put("testsToAdd", output.testsToAdd());
        payload.put("observabilityChecks", output.observabilityChecks());
        payload.put("rolloutNotes", output.rolloutNotes());
        payload.put("rollbackNotes", output.rollbackNotes());
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

    // ---- Defensive architecture-review parsing -----------------------------------------------

    ParsedReview parseReview(ReviewArchitectureInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String verdict = parseVerdict(sections.get("VERDICT"));
        String review = joinSection(sections.get("REVIEW"));
        List<ArchitectureFinding> findings = parseFindings(sections.get("FINDINGS"));
        List<ArchitectureRisk> riskMatrix = parseRiskMatrix(sections.get("RISK_MATRIX"));
        List<String> tradeOffs = bullets(sections.get("TRADE_OFFS"));
        List<String> alternatives = bullets(sections.get("ALTERNATIVES"));
        List<String> openQuestions = bullets(sections.get("OPEN_QUESTIONS"));
        List<String> testsToAdd = bullets(sections.get("TESTS_TO_ADD"));
        List<String> observabilityChecks = bullets(sections.get("OBSERVABILITY_CHECKS"));
        List<String> rolloutNotes = bullets(sections.get("ROLLOUT_NOTES"));
        List<String> rollbackNotes = bullets(sections.get("ROLLBACK_NOTES"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));

        if (findings.isEmpty() && riskMatrix.isEmpty() && review.isBlank() && tradeOffs.isEmpty()) {
            // Non-compliant response: keep the whole (truncated) text usable in review.
            review = text == null ? "" : text.strip();
        }
        return new ParsedReview(summary, verdict, review, findings, riskMatrix, tradeOffs,
                alternatives, openQuestions, testsToAdd, observabilityChecks, rolloutNotes,
                rollbackNotes, risks, safetyNotes, assumptions);
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

    private String buildSummary(ReviewArchitectureInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        return cap("Architecture review (" + input.architectureStyle().wireValue() + ", focus "
                + input.reviewFocus().wireValue() + "): " + input.task());
    }

    private String parseVerdict(List<String> verdictLines) {
        if (verdictLines != null) {
            for (String line : verdictLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    Optional<ArchitectureVerdict> v = ArchitectureVerdict.fromWire(candidate);
                    if (v.isPresent()) {
                        return v.get().wireValue();
                    }
                    // Try first token (e.g. "REQUEST_CHANGES â€” because ...").
                    String firstToken = candidate.split("[\\sâ€”:\\-]")[0];
                    Optional<ArchitectureVerdict> vt = ArchitectureVerdict.fromWire(firstToken);
                    if (vt.isPresent()) {
                        return vt.get().wireValue();
                    }
                }
            }
        }
        return ArchitectureVerdict.NEEDS_MORE_CONTEXT.wireValue();
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
    static List<ArchitectureFinding> parseFindings(List<String> lines) {
        List<ArchitectureFinding> findings = new ArrayList<>();
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
                // Bare bullet (no key): treat as a standalone finding title.
                if (current != null && current.hasContent()) {
                    findings.add(current.build());
                }
                current = new FindingBuilder();
                current.set("title", core);
            } else if (current != null) {
                // Continuation line: append to the details text.
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            findings.add(current.build());
        }
        return List.copyOf(findings);
    }

    /**
     * Parses the RISK_MATRIX block into structured rows. Defensive: supports key:value blocks
     * ({@code risk/likelihood/impact/mitigation}), markdown table rows
     * ({@code | risk | likelihood | impact | mitigation |}), and bare bullets; unknown
     * likelihood/impact values fall back to MEDIUM; a malformed entry never aborts parsing.
     */
    static List<ArchitectureRisk> parseRiskMatrix(List<String> lines) {
        List<ArchitectureRisk> rows = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return rows;
        }
        RiskBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || isFenceLine(line)) {
                continue;
            }

            // Markdown table row: | risk | likelihood | impact | mitigation |
            if (line.startsWith("|") && line.chars().filter(c -> c == '|').count() >= 3) {
                Optional<ArchitectureRisk> tableRow = parseTableRow(line);
                if (tableRow.isPresent()) {
                    if (current != null && current.hasContent()) {
                        rows.add(current.build());
                        current = null;
                    }
                    rows.add(tableRow.get());
                }
                continue;
            }

            boolean bullet = line.startsWith("-") || line.startsWith("*");
            String core = bullet ? line.substring(1).strip() : line;

            Matcher keyMatcher = RISK_KEY_PATTERN.matcher(core);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1).toLowerCase();
                String value = keyMatcher.group(2) == null ? "" : keyMatcher.group(2).strip();
                if (key.equals("risk") && current != null && current.hasContent()) {
                    rows.add(current.build());
                    current = null;
                }
                if (current == null) {
                    current = new RiskBuilder();
                }
                current.set(key, value);
            } else if (bullet) {
                if (current != null && current.hasContent()) {
                    rows.add(current.build());
                }
                current = new RiskBuilder();
                current.set("risk", core);
            } else if (current != null) {
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            rows.add(current.build());
        }
        return List.copyOf(rows);
    }

    private static Optional<ArchitectureRisk> parseTableRow(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] cells = trimmed.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].strip();
        }
        // Skip header/separator rows.
        String joined = String.join("", cells).replace("-", "").replace(":", "").strip();
        if (joined.isEmpty()) {
            return Optional.empty();
        }
        String first = cells[0].toLowerCase();
        if (first.equals("risk") && cells.length >= 2 && cells[1].equalsIgnoreCase("likelihood")) {
            return Optional.empty();
        }
        String risk = cells.length > 0 ? cells[0] : "";
        String likelihood = cells.length > 1 ? cells[1] : "";
        String impact = cells.length > 2 ? cells[2] : "";
        String mitigation = cells.length > 3 ? cells[3] : "";
        if (risk.isBlank() && likelihood.isBlank() && impact.isBlank() && mitigation.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ArchitectureRisk(
                risk,
                normalizeLevel(likelihood),
                normalizeLevel(impact),
                mitigation));
    }

    private static String normalizeSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return ArchitectureFinding.DEFAULT_SEVERITY;
        }
        String norm = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_SEVERITIES.contains(norm) ? norm : ArchitectureFinding.DEFAULT_SEVERITY;
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return ArchitectureFinding.DEFAULT_CATEGORY;
        }
        String norm = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_CATEGORIES.contains(norm) ? norm : ArchitectureFinding.DEFAULT_CATEGORY;
    }

    private static String normalizeLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return ArchitectureRisk.DEFAULT_LEVEL;
        }
        String norm = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_LEVELS.contains(norm) ? norm : ArchitectureRisk.DEFAULT_LEVEL;
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
            List<ArchitectureFinding> findings,
            List<ArchitectureRisk> riskMatrix,
            List<String> tradeOffs,
            List<String> alternatives,
            List<String> openQuestions,
            List<String> testsToAdd,
            List<String> observabilityChecks,
            List<String> rolloutNotes,
            List<String> rollbackNotes,
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

        ArchitectureFinding build() {
            String t = title != null ? title : (details != null ? details : "Finding");
            return new ArchitectureFinding(
                    normalizeSeverity(severity),
                    normalizeCategory(category),
                    t,
                    details == null ? "" : details,
                    recommendation == null ? "" : recommendation);
        }
    }

    private static final class RiskBuilder {
        private String risk;
        private String likelihood;
        private String impact;
        private String mitigation;

        void set(String key, String value) {
            switch (key) {
                case "risk" -> risk = value;
                case "likelihood" -> likelihood = value;
                case "impact" -> impact = value;
                case "mitigation" -> mitigation = value;
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        void appendContinuation(String text) {
            mitigation = append(mitigation, text);
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return isNonBlank(risk) || isNonBlank(likelihood) || isNonBlank(impact)
                    || isNonBlank(mitigation);
        }

        ArchitectureRisk build() {
            return new ArchitectureRisk(
                    risk == null ? "" : risk,
                    normalizeLevel(likelihood),
                    normalizeLevel(impact),
                    mitigation == null ? "" : mitigation);
        }
    }
}
