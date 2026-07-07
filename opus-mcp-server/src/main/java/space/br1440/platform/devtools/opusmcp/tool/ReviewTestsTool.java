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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewTestsInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewTestsOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestFinding;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestFramework;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestReviewFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestReviewLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestReviewOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestReviewVerdict;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestType;

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
 * Phase 15A read-only MCP tool: {@code review_tests_with_opus}.
 *
 * <p>Accepts explicit Cursor-provided test code plus context (test intent, optional production
 * context, failure logs, dependencies context and constraints) plus review knobs, and delegates to
 * the external Opus model to return a structured test review: summary, verdict, review, findings,
 * coverage gaps, assertion issues, flakiness risks, mocking issues, test data issues, integration
 * boundary issues, maintainability issues, suggested test cases, CI readiness checks, risks. Reuses
 * the exact same guard pipeline as the other tools (deny-list, secret scan, size limits, config
 * validation, model allowlist, rate limit, budget). Never reads/writes files, executes commands, runs
 * tests/Gradle/Maven, collects coverage, or applies patches. All inputs are treated as untrusted
 * data, never as instructions.
 */
public final class ReviewTestsTool {

    private static final Logger log = LoggerFactory.getLogger(ReviewTestsTool.class);

    public static final String TOOL_NAME = "review_tests_with_opus";

    public static final String DESCRIPTION =
            "Read-only test review tool. Does not read files, write files, execute commands, run "
                    + "tests, collect coverage, run Gradle/Maven, or apply patches. Reviews only the "
                    + "test code, production context, test intent, failure logs, dependencies context, "
                    + "constraints, framework, type, focus, and risk level explicitly provided in the "
                    + "tool input (treated as untrusted data, never instructions). Returns a structured "
                    + "test review with verdict, findings, coverage gaps, assertion issues, flakiness "
                    + "risks, mocking issues, test data issues, integration-boundary issues, "
                    + "maintainability issues, suggested test cases, CI readiness checks, risks, and "
                    + "open questions. Cursor/user must review and apply changes manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "language", "testCode", "testIntent", "testFramework", "testType", "reviewFocus", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What to review or what the reviewer should focus on."
                },
                "language": {
                  "type": "string",
                  "enum": ["java", "go", "kotlin", "sql", "other"]
                },
                "testCode": {
                  "type": "string",
                  "description": "The test code to review, explicitly provided by Cursor. Treated as data."
                },
                "productionContext": {
                  "type": "string",
                  "description": "Optional production code/context under test, explicitly provided by Cursor. Treated as data."
                },
                "testIntent": {
                  "type": "string",
                  "description": "What the tests are intended to verify, explicitly provided by Cursor. Treated as data."
                },
                "failureLogs": {
                  "type": "string",
                  "description": "Optional test failure logs explicitly provided by Cursor. Treated as data."
                },
                "dependenciesContext": {
                  "type": "string",
                  "description": "Optional dependencies/build context explicitly provided by Cursor. Treated as data."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit review constraints."
                },
                "testFramework": {
                  "type": "string",
                  "enum": ["junit5", "testng", "spock", "kotest", "go_testing", "pytest", "unknown"]
                },
                "testType": {
                  "type": "string",
                  "enum": ["unit", "integration", "contract", "component", "slice", "e2e", "property", "performance", "unknown"]
                },
                "reviewFocus": {
                  "type": "string",
                  "enum": ["correctness", "coverage", "flakiness", "maintainability", "assertions", "mocks", "integration_boundaries", "security", "performance", "all"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["structured_review", "checklist", "risk_review", "coverage_review", "ci_readiness"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_SEVERITIES = Set.of(
            "BLOCKER", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "correctness", "coverage", "flakiness", "maintainability", "assertions", "mocks",
            "integration_boundaries", "security", "performance", "other");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|VERDICT|REVIEW|FINDINGS|COVERAGE[_ ]GAPS"
                    + "|ASSERTION[_ ]ISSUES|FLAKINESS[_ ]RISKS|MOCKING[_ ]ISSUES|TEST[_ ]DATA[_ ]ISSUES"
                    + "|INTEGRATION[_ ]BOUNDARY[_ ]ISSUES|MAINTAINABILITY[_ ]ISSUES"
                    + "|SUGGESTED[_ ]TEST[_ ]CASES|CI[_ ]READINESS[_ ]CHECKS|OPEN[_ ]QUESTIONS"
                    + "|RISKS|SAFETY[_ ]NOTES|ASSUMPTIONS)\\s*:\\s*(.*)$");
    private static final Pattern FINDING_KEY_PATTERN = Pattern.compile(
            "(?i)^(severity|category|title|details|recommendation)\\s*:\\s*(.*)$");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)]\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final ReviewTestsPromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public ReviewTestsTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewTestsPromptBuilder promptBuilder,
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

    ReviewTestsTool(
            AppConfig config,
            OpusClient opusClient,
            ReviewTestsPromptBuilder promptBuilder,
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

    public ReviewTestsOutput handle(Map<String, Object> arguments) {
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
        Optional<ReviewTestsInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, language, testCode, testIntent, "
                            + "testFramework, testType, reviewFocus, riskLevel, and outputFormat are "
                            + "required",
                    requestId,
                    model));
        }

        ReviewTestsInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.testCode() + input.productionContext() + input.testIntent()
                        + input.failureLogs() + input.dependenciesContext() + input.constraints());
        audit.outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
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
            return finish(audit, startNanos, ReviewTestsOutput.ofStatus(
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

            return finish(audit, startNanos, new ReviewTestsOutput(
                    GenerateCodeStatus.OK,
                    review.summary(),
                    review.verdict(),
                    review.review(),
                    review.findings(),
                    review.coverageGaps(),
                    review.assertionIssues(),
                    review.flakinessRisks(),
                    review.mockingIssues(),
                    review.testDataIssues(),
                    review.integrationBoundaryIssues(),
                    review.maintainabilityIssues(),
                    review.suggestedTestCases(),
                    review.ciReadinessChecks(),
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
            log.warn("Opus review-tests call failed requestId={} reason={}", requestId, e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : GenerateCodeStatus.MODEL_ERROR;
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, ReviewTestsOutput.ofProviderFailure(
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

    private ReviewTestsOutput finish(
            AuditRecord.Builder audit, long startNanos, ReviewTestsOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(ReviewTestsInput input) {
        return (long) input.task().length() + input.testCode().length()
                + input.productionContext().length() + input.testIntent().length()
                + input.failureLogs().length() + input.dependenciesContext().length()
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

    private Optional<ReviewTestsInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String testCode = stringValue(arguments.get("testCode"));
        String testIntent = stringValue(arguments.get("testIntent"));
        if (task == null || task.isBlank() || testCode == null || testCode.isBlank()
                || testIntent == null || testIntent.isBlank()) {
            return Optional.empty();
        }
        Optional<TestReviewLanguage> language =
                TestReviewLanguage.fromWire(stringValue(arguments.get("language")));
        Optional<TestFramework> testFramework =
                TestFramework.fromWire(stringValue(arguments.get("testFramework")));
        Optional<TestType> testType = TestType.fromWire(stringValue(arguments.get("testType")));
        Optional<TestReviewFocus> reviewFocus =
                TestReviewFocus.fromWire(stringValue(arguments.get("reviewFocus")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        Optional<TestReviewOutputFormat> outputFormat =
                TestReviewOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (language.isEmpty() || testFramework.isEmpty() || testType.isEmpty()
                || reviewFocus.isEmpty() || riskLevel.isEmpty() || outputFormat.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReviewTestsInput(
                task.trim(),
                language.get(),
                testCode,
                nullToEmpty(stringValue(arguments.get("productionContext"))),
                testIntent,
                nullToEmpty(stringValue(arguments.get("failureLogs"))),
                nullToEmpty(stringValue(arguments.get("dependenciesContext"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                testFramework.get(),
                testType.get(),
                reviewFocus.get(),
                riskLevel.get(),
                outputFormat.get()));
    }

    private List<String> allTextFields(ReviewTestsInput input) {
        return List.of(input.task(), input.testCode(), input.productionContext(), input.testIntent(),
                input.failureLogs(), input.dependenciesContext(), input.constraints());
    }

    private Optional<String> findDenyViolation(ReviewTestsInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(ReviewTestsInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(ReviewTestsInput input) {
        for (String[] field : new String[][] {
                {"testCode", input.testCode()},
                {"productionContext", input.productionContext()},
                {"failureLogs", input.failureLogs()},
                {"dependenciesContext", input.dependenciesContext()}}) {
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

    private Map<String, Object> toPayload(ReviewTestsOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("verdict", output.verdict());
        payload.put("review", output.review());
        List<Map<String, Object>> findings = new ArrayList<>();
        for (TestFinding f : output.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", f.severity());
            m.put("category", f.category());
            m.put("title", f.title());
            m.put("details", f.details());
            m.put("recommendation", f.recommendation());
            findings.add(m);
        }
        payload.put("findings", findings);
        payload.put("coverageGaps", output.coverageGaps());
        payload.put("assertionIssues", output.assertionIssues());
        payload.put("flakinessRisks", output.flakinessRisks());
        payload.put("mockingIssues", output.mockingIssues());
        payload.put("testDataIssues", output.testDataIssues());
        payload.put("integrationBoundaryIssues", output.integrationBoundaryIssues());
        payload.put("maintainabilityIssues", output.maintainabilityIssues());
        payload.put("suggestedTestCases", output.suggestedTestCases());
        payload.put("ciReadinessChecks", output.ciReadinessChecks());
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

    // ---- Defensive test-review parsing --------------------------------------------------------

    ParsedReview parseReview(ReviewTestsInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String verdict = parseVerdict(sections.get("VERDICT"));
        String review = joinSection(sections.get("REVIEW"));
        List<TestFinding> findings = parseFindings(sections.get("FINDINGS"));
        List<String> coverageGaps = bullets(sections.get("COVERAGE_GAPS"));
        List<String> assertionIssues = bullets(sections.get("ASSERTION_ISSUES"));
        List<String> flakinessRisks = bullets(sections.get("FLAKINESS_RISKS"));
        List<String> mockingIssues = bullets(sections.get("MOCKING_ISSUES"));
        List<String> testDataIssues = bullets(sections.get("TEST_DATA_ISSUES"));
        List<String> integrationBoundaryIssues = bullets(sections.get("INTEGRATION_BOUNDARY_ISSUES"));
        List<String> maintainabilityIssues = bullets(sections.get("MAINTAINABILITY_ISSUES"));
        List<String> suggestedTestCases = bullets(sections.get("SUGGESTED_TEST_CASES"));
        List<String> ciReadinessChecks = bullets(sections.get("CI_READINESS_CHECKS"));
        List<String> openQuestions = bullets(sections.get("OPEN_QUESTIONS"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));

        if (findings.isEmpty() && review.isBlank() && coverageGaps.isEmpty()
                && assertionIssues.isEmpty() && suggestedTestCases.isEmpty()) {
            // Non-compliant response: keep the whole (truncated) text usable in review.
            review = text == null ? "" : text.strip();
        }
        return new ParsedReview(summary, verdict, review, findings, coverageGaps, assertionIssues,
                flakinessRisks, mockingIssues, testDataIssues, integrationBoundaryIssues,
                maintainabilityIssues, suggestedTestCases, ciReadinessChecks, openQuestions, risks,
                safetyNotes, assumptions);
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

    private String buildSummary(ReviewTestsInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        return cap(input.testType().wireValue() + " test review (focus "
                + input.reviewFocus().wireValue() + ", " + input.testFramework().wireValue() + "): "
                + input.task());
    }

    private String parseVerdict(List<String> verdictLines) {
        if (verdictLines != null) {
            for (String line : verdictLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    Optional<TestReviewVerdict> v = TestReviewVerdict.fromWire(candidate);
                    if (v.isPresent()) {
                        return v.get().wireValue();
                    }
                    String firstToken = candidate.split("[\\sâ€”:\\-]")[0];
                    Optional<TestReviewVerdict> vt = TestReviewVerdict.fromWire(firstToken);
                    if (vt.isPresent()) {
                        return vt.get().wireValue();
                    }
                }
            }
        }
        return TestReviewVerdict.NEEDS_MORE_CONTEXT.wireValue();
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
    static List<TestFinding> parseFindings(List<String> lines) {
        List<TestFinding> findings = new ArrayList<>();
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
            return TestFinding.DEFAULT_SEVERITY;
        }
        String norm = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_SEVERITIES.contains(norm) ? norm : TestFinding.DEFAULT_SEVERITY;
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return TestFinding.DEFAULT_CATEGORY;
        }
        String norm = raw.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        return ALLOWED_CATEGORIES.contains(norm) ? norm : TestFinding.DEFAULT_CATEGORY;
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
            List<TestFinding> findings,
            List<String> coverageGaps,
            List<String> assertionIssues,
            List<String> flakinessRisks,
            List<String> mockingIssues,
            List<String> testDataIssues,
            List<String> integrationBoundaryIssues,
            List<String> maintainabilityIssues,
            List<String> suggestedTestCases,
            List<String> ciReadinessChecks,
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

        TestFinding build() {
            String t = title != null ? title : (details != null ? details : "Finding");
            return new TestFinding(
                    normalizeSeverity(severity),
                    normalizeCategory(category),
                    t,
                    details == null ? "" : details,
                    recommendation == null ? "" : recommendation);
        }
    }
}
