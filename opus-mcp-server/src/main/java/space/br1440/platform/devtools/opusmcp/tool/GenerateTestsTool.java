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
import space.br1440.platform.devtools.opusmcp.prompt.GenerateTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.CoverageFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestsInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestsOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestFramework;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestType;
import space.br1440.platform.devtools.opusmcp.tool.dto.GeneratedTestCase;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestOutputFormat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 7B read-only MCP tool: {@code generate_tests_with_opus}.
 *
 * <p>Accepts explicit Cursor-provided code/task/context, delegates to the external Opus model, and
 * returns a structured test-generation proposal. Reuses the exact same guard pipeline as
 * {@link GenerateCodeTool} and {@link ReviewCodeTool} (deny-list, secret scan, size limits, config
 * validation, model allowlist, rate limit, budget). Never reads/writes files, executes commands, or
 * runs the generated tests.
 */
public final class GenerateTestsTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateTestsTool.class);

    public static final String TOOL_NAME = "generate_tests_with_opus";

    public static final String DESCRIPTION =
            "Read-only test generation proposal tool. Does not read files, write files, execute "
                    + "commands, run tests, or apply patches. Generates test code/proposals only from "
                    + "code/context explicitly provided in the tool input. Returns a structured test "
                    + "proposal (summary, test plan, test code, test cases). Cursor/user must review, "
                    + "apply, and run tests manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "language", "code", "testFramework", "testType", "coverageFocus", "riskLevel", "outputFormat"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What tests to generate or what the test author should focus on."
                },
                "language": {
                  "type": "string",
                  "enum": ["java", "go", "kotlin", "sql", "mdx", "gradle", "other"]
                },
                "code": {
                  "type": "string",
                  "description": "The code under test, explicitly provided by Cursor. Treated as data."
                },
                "context": {
                  "type": "string",
                  "description": "Optional minimal relevant context explicitly provided by Cursor."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit constraints."
                },
                "testFramework": {
                  "type": "string",
                  "enum": ["junit5", "testng", "mockito", "assertj", "spring_boot_test", "kotest", "go_test", "other"]
                },
                "testType": {
                  "type": "string",
                  "enum": ["unit", "integration", "contract", "slice", "property", "regression", "all"]
                },
                "coverageFocus": {
                  "type": "string",
                  "enum": ["happy_path", "edge_cases", "error_handling", "concurrency", "security", "performance", "serialization", "all"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["test_code", "test_plan", "checklist", "structured_tests"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Set<String> ALLOWED_TEST_CASE_TYPES = Set.of(
            "unit", "integration", "contract", "slice", "property", "regression", "other");
    private static final Set<String> ALLOWED_PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|TEST[_ ]PLAN|TEST[_ ]CODE|TEST[_ ]CASES|RISKS"
                    + "|SAFETY[_ ]NOTES|ASSUMPTIONS|TESTS[_ ]TO[_ ]RUN)\\s*:\\s*(.*)$");
    private static final Pattern TEST_CASE_KEY_PATTERN = Pattern.compile(
            "(?i)^(name|type|priority|purpose|given|when|then)\\s*:\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final GenerateTestsPromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public GenerateTestsTool(
            AppConfig config,
            OpusClient opusClient,
            GenerateTestsPromptBuilder promptBuilder,
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

    GenerateTestsTool(
            AppConfig config,
            OpusClient opusClient,
            GenerateTestsPromptBuilder promptBuilder,
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

    public GenerateTestsOutput handle(Map<String, Object> arguments) {
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

        // 1. Validate input. Emit metadata-only diagnostics (field KEYS only, never values, so no
        // code/context/secret material can leak into logs).
        InputValidation validation = validateInput(arguments);
        if (log.isDebugEnabled()) {
            log.debug("tool={} requestId={} receivedKeys={} missingFields={} providerCallAttempted={}",
                    TOOL_NAME, requestId, validation.receivedKeys(), validation.missingFields(), false);
        }
        if (validation.input() == null) {
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input; required fields missing or invalid: "
                            + String.join(", ", validation.missingFields())
                            + " (required: " + String.join(", ", REQUIRED_FIELDS) + ")",
                    requestId,
                    model));
        }

        GenerateTestsInput input = validation.input();
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
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
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
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
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
            ParsedTests tests = parseTests(input, truncated.value());

            // The provider was called and returned tokens: record budget and token estimates even if
            // the response turns out to be unparseable, so estimates are never lost on parse failure.
            budgetTracker.record(inputChars,
                    response.inputTokenEstimate(), response.outputTokenEstimate());

            audit.estimatedInputTokens(response.inputTokenEstimate())
                    .estimatedOutputTokens(response.outputTokenEstimate())
                    .estimatedCost(budgetTracker.snapshot().estimatedCost())
                    .httpStatusCategory("2xx");

            if (tests.parseFailed()) {
                if (log.isDebugEnabled()) {
                    log.debug("tool={} requestId={} parseOutcome=unparseable outputFormat={} "
                                    + "providerCallAttempted=true",
                            TOOL_NAME, requestId, input.outputFormat().wireValue());
                }
                return finish(audit, startNanos, new GenerateTestsOutput(
                        GenerateCodeStatus.MODEL_ERROR,
                        PARSE_FAILURE_SUMMARY,
                        "",
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        truncated.truncated(),
                        response.inputTokenEstimate(),
                        response.outputTokenEstimate(),
                        model,
                        requestId));
            }

            return finish(audit, startNanos, new GenerateTestsOutput(
                    GenerateCodeStatus.OK,
                    tests.summary(),
                    tests.testPlan(),
                    tests.testCode(),
                    tests.testCases(),
                    tests.risks(),
                    tests.safetyNotes(),
                    tests.assumptions(),
                    tests.testsToRun(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus test-generation call failed requestId={} reason={}", requestId, e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : GenerateCodeStatus.MODEL_ERROR;
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, GenerateTestsOutput.ofStatus(
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
                    + "\"testPlan\":\"\",\"testCode\":\"\",\"testCases\":[]}";
        }
    }

    private GenerateTestsOutput finish(
            AuditRecord.Builder audit, long startNanos, GenerateTestsOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(GenerateTestsInput input) {
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

    private static final List<String> REQUIRED_FIELDS = List.of(
            "task", "language", "code", "testFramework", "testType", "coverageFocus",
            "riskLevel", "outputFormat");

    /**
     * Validates and binds the raw MCP {@code tools/call} arguments map into a {@link
     * GenerateTestsInput}. The arguments map is the exact {@code params.arguments} object that the
     * MCP transport hands to the tool (see {@code McpServerFactory#handleGenerateTests}); this method
     * therefore reads the same map level Cursor populates with structured fields.
     *
     * <p>The returned {@link InputValidation} carries metadata (received field keys, missing/invalid
     * field keys) so callers can log a diagnostic without ever touching field VALUES.
     */
    InputValidation validateInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return new InputValidation(null, List.of(), REQUIRED_FIELDS);
        }
        List<String> receivedKeys = List.copyOf(new TreeSet<>(arguments.keySet()));
        List<String> missing = new ArrayList<>();

        String task = stringValue(arguments.get("task"));
        if (task == null || task.isBlank()) {
            missing.add("task");
        }
        String code = stringValue(arguments.get("code"));
        if (code == null || code.isBlank()) {
            missing.add("code");
        }
        Optional<CodeLanguage> language = CodeLanguage.fromWire(stringValue(arguments.get("language")));
        if (language.isEmpty()) {
            missing.add("language");
        }
        Optional<GenerateTestFramework> testFramework =
                GenerateTestFramework.fromWire(stringValue(arguments.get("testFramework")));
        if (testFramework.isEmpty()) {
            missing.add("testFramework");
        }
        Optional<GenerateTestType> testType =
                GenerateTestType.fromWire(stringValue(arguments.get("testType")));
        if (testType.isEmpty()) {
            missing.add("testType");
        }
        Optional<CoverageFocus> coverageFocus =
                CoverageFocus.fromWire(stringValue(arguments.get("coverageFocus")));
        if (coverageFocus.isEmpty()) {
            missing.add("coverageFocus");
        }
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        if (riskLevel.isEmpty()) {
            missing.add("riskLevel");
        }
        Optional<TestOutputFormat> outputFormat =
                TestOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        if (outputFormat.isEmpty()) {
            missing.add("outputFormat");
        }

        if (!missing.isEmpty()) {
            return new InputValidation(null, receivedKeys, List.copyOf(missing));
        }
        GenerateTestsInput input = new GenerateTestsInput(
                task.trim(),
                language.get(),
                code,
                nullToEmpty(stringValue(arguments.get("context"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                testFramework.get(),
                testType.get(),
                coverageFocus.get(),
                riskLevel.get(),
                outputFormat.get());
        return new InputValidation(input, receivedKeys, List.of());
    }

    /**
     * Result of {@link #validateInput(Map)}: the bound input (or {@code null} when invalid) plus
     * metadata-only diagnostics (field keys, never values).
     */
    record InputValidation(GenerateTestsInput input, List<String> receivedKeys,
            List<String> missingFields) {
    }

    private Optional<String> findDenyViolation(GenerateTestsInput input) {
        for (String field : List.of(input.task(), input.code(), input.context(), input.constraints())) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(GenerateTestsInput input) {
        for (String field : List.of(input.task(), input.code(), input.context(), input.constraints())) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(GenerateTestsInput input) {
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

    private Map<String, Object> toPayload(GenerateTestsOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("testPlan", output.testPlan());
        payload.put("testCode", output.testCode());
        List<Map<String, Object>> testCases = new ArrayList<>();
        for (GeneratedTestCase tc : output.testCases()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", tc.name());
            m.put("type", tc.type());
            m.put("purpose", tc.purpose());
            m.put("given", tc.given());
            m.put("when", tc.when());
            m.put("then", tc.then());
            m.put("priority", tc.priority());
            testCases.add(m);
        }
        payload.put("testCases", testCases);
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

    // ---- Defensive test-proposal parsing ------------------------------------------------------

    static final String PARSE_FAILURE_SUMMARY =
            "Could not parse provider response: no TEST_CODE section, fenced Java block, or Java "
                    + "source detected";

    ParsedTests parseTests(GenerateTestsInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String testPlan = joinSection(sections.get("TEST_PLAN"));
        String testCode = extractCode(sections.get("TEST_CODE"));
        List<GeneratedTestCase> testCases = parseTestCases(sections.get("TEST_CASES"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));
        List<String> testsToRun = bullets(sections.get("TESTS_TO_RUN"));

        // Fallback recovery when the model did not use a TEST_CODE section: accept a fenced code
        // block (fenced-only or prose+fence responses) or a plain Java source body. This preserves
        // package/import/annotations/class content exactly and never folds prose into testCode.
        if (testCode.isBlank()) {
            testCode = recoverTestCode(text);
        }

        boolean hasContent = !testPlan.isBlank() || !testCode.isBlank() || !testCases.isEmpty();
        if (!hasContent) {
            if (input.outputFormat() == TestOutputFormat.TEST_CODE) {
                // test_code was requested but no section, fenced block, or Java source was found:
                // classify as a safe, diagnosable parse failure rather than a silent empty result.
                return ParsedTests.failed(summary);
            }
            // Other output formats: keep the whole (truncated) text usable in the testPlan field.
            testPlan = text == null ? "" : text.strip();
        }
        return new ParsedTests(summary, testPlan, testCode, testCases, risks, safetyNotes,
                assumptions, testsToRun, false);
    }

    /**
     * Recovers Java test code from a non-sectioned response: the first fenced code block if present
     * (markdown-fence tolerant), otherwise the whole body when it looks like Java source. Returns an
     * empty string when neither is detected.
     */
    private static String recoverTestCode(String text) {
        Optional<String> fenced = extractPreferredJavaFence(text);
        if (fenced.isPresent()) {
            return fenced.get();
        }
        if (looksLikeJavaSource(text)) {
            return trimOuterBlankLines(normalizeForParsing(text).strip());
        }
        return "";
    }

    /**
     * Normalizes provider text for parsing only: strips BOM and converts line endings to LF.
     * Returned {@code testCode} uses LF line endings from fence/section extraction; semantic
     * content (package, imports, annotations) is never rewritten.
     */
    static String normalizeForParsing(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String noBom = text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
        return noBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Line-anchored fence scanner: prefers a {@code java}-labeled fence or Java-like body, falls
     * back to the first non-empty fenced block. Closing fences must match the opening tick/tilde
     * count on a line that contains only fence characters (so {@code ```} inside a string literal
     * does not close a three-tick fence).
     */
    static Optional<String> extractPreferredJavaFence(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] lines = normalizeForParsing(text).split("\n", -1);
        Optional<String> firstAny = Optional.empty();

        for (int i = 0; i < lines.length; i++) {
            Fence open = Fence.tryOpen(lines[i]);
            if (open == null) {
                continue;
            }
            int bodyStart = i + 1;
            for (int j = bodyStart; j < lines.length; j++) {
                if (!open.matchesClose(lines[j])) {
                    continue;
                }
                String candidate = trimOuterBlankLines(joinLines(lines, bodyStart, j));
                if (candidate.isEmpty()) {
                    break;
                }
                if (looksLikeJavaSource(candidate) || "java".equalsIgnoreCase(open.language())) {
                    return Optional.of(candidate);
                }
                if (firstAny.isEmpty()) {
                    firstAny = Optional.of(candidate);
                }
                break;
            }
        }
        return firstAny;
    }

    private static String joinLines(String[] lines, int startInclusive, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            if (i > startInclusive) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    static String trimOuterBlankLines(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        int start = 0;
        int end = lines.length;
        while (start < end && lines[start].isBlank()) {
            start++;
        }
        while (end > start && lines[end - 1].isBlank()) {
            end--;
        }
        if (start >= end) {
            return "";
        }
        return joinLines(lines, start, end);
    }

    private static final Pattern JAVA_SOURCE_PATTERN = Pattern.compile(
            "(?m)^\\s*(package\\s+[\\w.]+\\s*;|import\\s+[\\w.*]+\\s*;"
                    + "|(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|strictfp\\s+)*"
                    + "(?:class|interface|enum|record|@interface)\\s+\\w+)");

    /** Conservative heuristic: does the text look like a Java source body (not prose)? */
    static boolean looksLikeJavaSource(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = normalizeForParsing(text).strip();
        if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
            return true;
        }
        return JAVA_SOURCE_PATTERN.matcher(trimmed).find() || trimmed.contains("@Test");
    }

    /** Splits the response into recognized sections, fence-aware. Returns raw body lines per section. */
    static Map<String, List<String>> splitSections(String text) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String normalized = normalizeForParsing(text);
        if (normalized.isBlank()) {
            return sections;
        }
        String[] lines = normalized.split("\n", -1);
        String current = null;
        boolean inFence = false;
        Fence activeFence = null;
        for (String raw : lines) {
            if (inFence && activeFence != null && activeFence.matchesClose(raw)) {
                inFence = false;
                activeFence = null;
                if (current != null) {
                    sections.get(current).add(raw);
                }
                continue;
            }
            if (!inFence) {
                Fence open = Fence.tryOpen(raw);
                if (open != null) {
                    inFence = true;
                    activeFence = open;
                    if (current != null) {
                        sections.get(current).add(raw);
                    }
                    continue;
                }
            } else if (current != null) {
                sections.get(current).add(raw);
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

    private String buildSummary(GenerateTestsInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        String languageLabel = capitalize(input.language().wireValue());
        return cap("Test generation (" + input.testType().wireValue() + ", "
                + input.coverageFocus().wireValue() + ") for " + languageLabel + ": " + input.task());
    }

    /** Joins a section, unwrapping a single leading/trailing code fence if present. */
    private static String extractCode(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<String> body = new ArrayList<>(lines);
        if (!body.isEmpty()) {
            Fence open = Fence.tryOpen(body.get(0));
            if (open != null) {
                body.remove(0);
                if (!body.isEmpty() && open.matchesClose(body.get(body.size() - 1))) {
                    body.remove(body.size() - 1);
                }
            }
        }
        return String.join("\n", body).strip();
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
     * Parses the TEST_CASES block into structured test cases. Defensive: a new test case starts at a
     * {@code name:} line (optionally bulleted) or a bare bullet; unknown types/priorities fall back
     * to defaults; a malformed entry never aborts parsing of the rest.
     */
    static List<GeneratedTestCase> parseTestCases(List<String> lines) {
        List<GeneratedTestCase> cases = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return cases;
        }
        TestCaseBuilder current = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean bullet = line.startsWith("-") || line.startsWith("*");
            String core = bullet ? line.substring(1).strip() : line;

            Matcher keyMatcher = TEST_CASE_KEY_PATTERN.matcher(core);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1).toLowerCase();
                String value = keyMatcher.group(2) == null ? "" : keyMatcher.group(2).strip();
                if (key.equals("name") && current != null && current.hasContent()) {
                    cases.add(current.build());
                    current = null;
                }
                if (current == null) {
                    current = new TestCaseBuilder();
                }
                current.set(key, value);
            } else if (bullet) {
                // Bare bullet (no key): treat as a standalone test case name.
                if (current != null && current.hasContent()) {
                    cases.add(current.build());
                }
                current = new TestCaseBuilder();
                current.set("name", core);
            } else if (current != null) {
                // Continuation line: append to the most recently populated free-text field.
                current.appendContinuation(line);
            }
        }
        if (current != null && current.hasContent()) {
            cases.add(current.build());
        }
        return List.copyOf(cases);
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return GeneratedTestCase.DEFAULT_TYPE;
        }
        String low = raw.trim().toLowerCase();
        return ALLOWED_TEST_CASE_TYPES.contains(low) ? low : GeneratedTestCase.DEFAULT_TYPE;
    }

    private static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return GeneratedTestCase.DEFAULT_PRIORITY;
        }
        String up = raw.trim().toUpperCase();
        return ALLOWED_PRIORITIES.contains(up) ? up : GeneratedTestCase.DEFAULT_PRIORITY;
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

    /** Line-anchored markdown fence marker for section splitting and code extraction. */
    private static final class Fence {
        private final char marker;
        private final int length;
        private final String language;

        private Fence(char marker, int length, String language) {
            this.marker = marker;
            this.length = length;
            this.language = language == null ? "" : language;
        }

        String language() {
            return language;
        }

        static Fence tryOpen(String line) {
            if (line == null) {
                return null;
            }
            String trimmed = line.trim();
            if (trimmed.length() < 3) {
                return null;
            }
            char first = trimmed.charAt(0);
            if (first != '`' && first != '~') {
                return null;
            }
            int count = 0;
            while (count < trimmed.length() && trimmed.charAt(count) == first) {
                count++;
            }
            if (count < 3) {
                return null;
            }
            String rest = trimmed.substring(count).trim();
            String lang = "";
            if (!rest.isEmpty()) {
                lang = rest.split("\\s+")[0];
            }
            return new Fence(first, count, lang);
        }

        boolean matchesClose(String line) {
            if (line == null) {
                return false;
            }
            String trimmed = line.trim();
            if (trimmed.length() < length) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (trimmed.charAt(i) != marker) {
                    return false;
                }
            }
            return trimmed.substring(length).trim().isEmpty();
        }
    }

    record ParsedTests(
            String summary,
            String testPlan,
            String testCode,
            List<GeneratedTestCase> testCases,
            List<String> risks,
            List<String> safetyNotes,
            List<String> assumptions,
            List<String> testsToRun,
            boolean parseFailed) {

        static ParsedTests failed(String summary) {
            return new ParsedTests(summary, "", "", List.of(), List.of(), List.of(), List.of(),
                    List.of(), true);
        }
    }

    private static final class TestCaseBuilder {
        private String name;
        private String type;
        private String priority;
        private String purpose;
        private String given;
        private String when;
        private String then;
        private String lastFreeTextField;

        void set(String key, String value) {
            switch (key) {
                case "name" -> {
                    name = value;
                    lastFreeTextField = "name";
                }
                case "type" -> type = value;
                case "priority" -> priority = value;
                case "purpose" -> {
                    purpose = value;
                    lastFreeTextField = "purpose";
                }
                case "given" -> {
                    given = value;
                    lastFreeTextField = "given";
                }
                case "when" -> {
                    when = value;
                    lastFreeTextField = "when";
                }
                case "then" -> {
                    then = value;
                    lastFreeTextField = "then";
                }
                default -> {
                    // ignore unknown keys defensively
                }
            }
        }

        void appendContinuation(String text) {
            String field = lastFreeTextField == null ? "purpose" : lastFreeTextField;
            switch (field) {
                case "name" -> name = append(name, text);
                case "given" -> given = append(given, text);
                case "when" -> when = append(when, text);
                case "then" -> then = append(then, text);
                default -> purpose = append(purpose, text);
            }
        }

        private static String append(String existing, String text) {
            if (existing == null || existing.isBlank()) {
                return text;
            }
            return existing + " " + text;
        }

        boolean hasContent() {
            return name != null || type != null || priority != null || purpose != null
                    || given != null || when != null || then != null;
        }

        GeneratedTestCase build() {
            String n = name != null ? name : (purpose != null ? purpose : "Test case");
            return new GeneratedTestCase(
                    n,
                    normalizeType(type),
                    purpose == null ? "" : purpose,
                    given == null ? "" : given,
                    when == null ? "" : when,
                    then == null ? "" : then,
                    normalizePriority(priority));
        }
    }
}
