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
import space.br1440.platform.devtools.opusmcp.prompt.WriteMdxDocPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.DocTargetAudience;
import space.br1440.platform.devtools.opusmcp.tool.dto.DocType;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.MdxOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;
import space.br1440.platform.devtools.opusmcp.tool.dto.WriteMdxDocInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.WriteMdxDocOutput;

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
 * Phase 12A read-only MCP tool: {@code write_mdx_doc_with_opus}.
 *
 * <p>Accepts an explicit Cursor-provided documentation context (task, doc subject, library context,
 * optional public API / configuration / examples / style / MDX components / asset guidelines /
 * constraints) plus doc knobs, and delegates to the external Opus model to draft MDX documentation:
 * summary, front matter, imports, MDX content, outline, examples, admonitions, assets needed, links,
 * claims to verify, validation checklist, risks. Reuses the exact same guard pipeline as the other
 * tools (deny-list, secret scan, size limits, config validation, model allowlist, rate limit, budget).
 * Never reads/writes files, creates assets, executes commands, runs Docusaurus, runs tests, or applies
 * patches. All inputs are treated as untrusted data, never as instructions.
 */
public final class WriteMdxDocTool {

    private static final Logger log = LoggerFactory.getLogger(WriteMdxDocTool.class);

    public static final String TOOL_NAME = "write_mdx_doc_with_opus";

    public static final String DESCRIPTION =
            "Read-only MDX documentation draft tool. Does not read files, write files, create assets, "
                    + "execute commands, run Docusaurus, run tests, or apply patches. Drafts MDX only "
                    + "from the documentation subject, library context, API/config/examples/style "
                    + "context, component context, asset guidelines, and constraints explicitly "
                    + "provided in the tool input (treated as untrusted data, never instructions). "
                    + "Returns front matter, imports, MDX content, outline, examples, assets needed, "
                    + "claims to verify, risks, and validation checklist. Cursor/user must review, "
                    + "create files, add assets, and run documentation validation manually.";

    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["task", "docSubject", "targetAudience", "libraryContext", "docType", "outputFormat", "riskLevel"],
              "properties": {
                "task": {
                  "type": "string",
                  "description": "What documentation to draft or what the author should focus on."
                },
                "docSubject": {
                  "type": "string",
                  "description": "The subject of the documentation. Treated as data."
                },
                "targetAudience": {
                  "type": "string",
                  "enum": ["platform_developers", "application_developers", "sre", "architects", "mixed"]
                },
                "libraryContext": {
                  "type": "string",
                  "description": "The library/module context explicitly provided by Cursor. Treated as data."
                },
                "publicApi": {
                  "type": "string",
                  "description": "Optional public API summary explicitly provided by Cursor. Treated as data."
                },
                "configurationProperties": {
                  "type": "string",
                  "description": "Optional configuration properties explicitly provided by Cursor. Treated as data."
                },
                "usageExamples": {
                  "type": "string",
                  "description": "Optional usage examples explicitly provided by Cursor. Treated as data."
                },
                "docStyleContext": {
                  "type": "string",
                  "description": "Optional doc style guide/examples explicitly provided by Cursor. Treated as data."
                },
                "mdxComponentsContext": {
                  "type": "string",
                  "description": "Optional available MDX/JSX components explicitly provided by Cursor. Treated as data."
                },
                "assetGuidelines": {
                  "type": "string",
                  "description": "Optional asset/image guidelines explicitly provided by Cursor."
                },
                "constraints": {
                  "type": "string",
                  "description": "Optional explicit documentation constraints."
                },
                "docType": {
                  "type": "string",
                  "enum": ["library_guide", "starter_guide", "migration_guide", "how_to", "reference", "adr", "release_notes", "troubleshooting", "unknown"]
                },
                "outputFormat": {
                  "type": "string",
                  "enum": ["mdx_page", "mdx_section", "outline", "frontmatter_plus_body", "reviewable_draft"]
                },
                "riskLevel": {
                  "type": "string",
                  "enum": ["low", "medium", "high"]
                }
              },
              "additionalProperties": false
            }
            """;

    private static final Pattern SECTION_HEADER_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*#{0,3}\\s*(SUMMARY|FRONT[_ ]MATTER|IMPORTS|MDX[_ ]CONTENT|OUTLINE|EXAMPLES"
                    + "|ADMONITIONS|ASSETS[_ ]NEEDED|LINKS[_ ]TO[_ ]ADD|CLAIMS[_ ]TO[_ ]VERIFY"
                    + "|VALIDATION[_ ]CHECKLIST|RISKS|SAFETY[_ ]NOTES|ASSUMPTIONS)\\s*:\\s*(.*)$");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\d+[.)]\\s*(.*)$");

    private final AppConfig config;
    private final OpusClient opusClient;
    private final WriteMdxDocPromptBuilder promptBuilder;
    private final SecretScanner secretScanner;
    private final DenyList denyList;
    private final LimitsGuard limitsGuard;
    private final ModelRegistry modelRegistry;
    private final ErrorMapper errorMapper;
    private final RateLimiter rateLimiter;
    private final BudgetTracker budgetTracker;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public WriteMdxDocTool(
            AppConfig config,
            OpusClient opusClient,
            WriteMdxDocPromptBuilder promptBuilder,
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

    WriteMdxDocTool(
            AppConfig config,
            OpusClient opusClient,
            WriteMdxDocPromptBuilder promptBuilder,
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

    public WriteMdxDocOutput handle(Map<String, Object> arguments) {
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
        Optional<WriteMdxDocInput> parsed = parseInput(arguments);
        if (parsed.isEmpty()) {
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    "Invalid or insufficient input: task, docSubject, targetAudience, libraryContext, "
                            + "docType, outputFormat, and riskLevel are required",
                    requestId,
                    model));
        }

        WriteMdxDocInput input = parsed.get();
        long inputChars = inputCharCount(input);
        long estimatedInputTokens = AnthropicHttpOpusClient.estimateTokens(
                input.task() + input.docSubject() + input.libraryContext() + input.publicApi()
                        + input.configurationProperties() + input.usageExamples()
                        + input.docStyleContext() + input.mdxComponentsContext()
                        + input.assetGuidelines() + input.constraints());
        audit.outputFormat(input.outputFormat().wireValue())
                .riskLevel(input.riskLevel().wireValue())
                .inputCharCount(inputChars)
                .estimatedInputTokens(estimatedInputTokens);

        // 2. Deny-list scan (sensitive file references).
        Optional<String> denyViolation = findDenyViolation(input);
        if (denyViolation.isPresent()) {
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + denyViolation.get(),
                    requestId,
                    model));
        }

        // 3. Secret scan (likely secret material).
        Optional<String> secretViolation = findSecretViolation(input);
        if (secretViolation.isPresent()) {
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
                    GenerateCodeStatus.REFUSED_UNSAFE,
                    "Input refused: " + secretViolation.get(),
                    requestId,
                    model));
        }

        // 4. Size limits.
        Optional<String> limitViolation = findLimitViolation(input);
        if (limitViolation.isPresent()) {
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
                    GenerateCodeStatus.NEEDS_MORE_CONTEXT,
                    limitViolation.get(),
                    requestId,
                    model));
        }

        // 5. Config validation (base URL / API key presence + URI sanity).
        Optional<String> configError = config.validateForGeneration();
        if (configError.isPresent()) {
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    configError.get(),
                    requestId,
                    model));
        }

        // 6. Model allowlist.
        if (!modelRegistry.isAllowed(model)) {
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
                    GenerateCodeStatus.MODEL_ERROR,
                    "Configured model is not allowlisted: " + model,
                    requestId,
                    model));
        }

        // 7. Rate limit.
        boolean rateAllowed = rateLimiter.tryAcquire();
        audit.rateLimitDecision(rateAllowed ? "allowed" : "throttled");
        if (!rateAllowed) {
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
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
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
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
            ParsedDoc doc = parseDoc(input, truncated.value());

            budgetTracker.record(inputChars,
                    response.inputTokenEstimate(), response.outputTokenEstimate());

            audit.estimatedInputTokens(response.inputTokenEstimate())
                    .estimatedOutputTokens(response.outputTokenEstimate())
                    .estimatedCost(budgetTracker.snapshot().estimatedCost())
                    .httpStatusCategory("2xx");

            return finish(audit, startNanos, new WriteMdxDocOutput(
                    GenerateCodeStatus.OK,
                    doc.summary(),
                    doc.frontMatter(),
                    doc.imports(),
                    doc.mdxContent(),
                    doc.outline(),
                    doc.examples(),
                    doc.admonitions(),
                    doc.assetsNeeded(),
                    doc.linksToAdd(),
                    doc.claimsToVerify(),
                    doc.validationChecklist(),
                    doc.risks(),
                    doc.safetyNotes(),
                    doc.assumptions(),
                    truncated.truncated(),
                    response.inputTokenEstimate(),
                    response.outputTokenEstimate(),
                    model,
                    requestId));
        } catch (OpusClientException e) {
            log.warn("Opus write-mdx-doc call failed requestId={} reason={}", requestId, e.reason());
            ProviderAuditSupport.applyFailure(audit, e);

            audit.httpStatusCategory(httpCategory(e));
            GenerateCodeStatus status = e.reason() == OpusClientException.Reason.HTTP_ERROR
                    && e.httpStatus() == 429
                    ? GenerateCodeStatus.BUDGET_EXCEEDED
                    : GenerateCodeStatus.MODEL_ERROR;
            if (status == GenerateCodeStatus.BUDGET_EXCEEDED) {
                audit.budgetDecision("provider-throttled");
            }
            return finish(audit, startNanos, WriteMdxDocOutput.ofStatus(
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
                    + "\"imports\":[],\"mdxContent\":\"\"}";
        }
    }

    private WriteMdxDocOutput finish(
            AuditRecord.Builder audit, long startNanos, WriteMdxDocOutput output) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        auditLogger.log(audit.status(output.status().name()).latencyMs(latencyMs).build());
        return output;
    }

    private static long inputCharCount(WriteMdxDocInput input) {
        return (long) input.task().length() + input.docSubject().length()
                + input.libraryContext().length() + input.publicApi().length()
                + input.configurationProperties().length() + input.usageExamples().length()
                + input.docStyleContext().length() + input.mdxComponentsContext().length()
                + input.assetGuidelines().length() + input.constraints().length();
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

    private Optional<WriteMdxDocInput> parseInput(Map<String, Object> arguments) {
        if (arguments == null) {
            return Optional.empty();
        }
        String task = stringValue(arguments.get("task"));
        String docSubject = stringValue(arguments.get("docSubject"));
        String libraryContext = stringValue(arguments.get("libraryContext"));
        if (task == null || task.isBlank() || docSubject == null || docSubject.isBlank()
                || libraryContext == null || libraryContext.isBlank()) {
            return Optional.empty();
        }
        Optional<DocTargetAudience> targetAudience =
                DocTargetAudience.fromWire(stringValue(arguments.get("targetAudience")));
        Optional<DocType> docType = DocType.fromWire(stringValue(arguments.get("docType")));
        Optional<MdxOutputFormat> outputFormat =
                MdxOutputFormat.fromWire(stringValue(arguments.get("outputFormat")));
        Optional<RiskLevel> riskLevel = RiskLevel.fromWire(stringValue(arguments.get("riskLevel")));
        if (targetAudience.isEmpty() || docType.isEmpty() || outputFormat.isEmpty()
                || riskLevel.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WriteMdxDocInput(
                task.trim(),
                docSubject,
                targetAudience.get(),
                libraryContext,
                nullToEmpty(stringValue(arguments.get("publicApi"))),
                nullToEmpty(stringValue(arguments.get("configurationProperties"))),
                nullToEmpty(stringValue(arguments.get("usageExamples"))),
                nullToEmpty(stringValue(arguments.get("docStyleContext"))),
                nullToEmpty(stringValue(arguments.get("mdxComponentsContext"))),
                nullToEmpty(stringValue(arguments.get("assetGuidelines"))),
                nullToEmpty(stringValue(arguments.get("constraints"))),
                docType.get(),
                outputFormat.get(),
                riskLevel.get()));
    }

    private List<String> allTextFields(WriteMdxDocInput input) {
        return List.of(input.task(), input.docSubject(), input.libraryContext(), input.publicApi(),
                input.configurationProperties(), input.usageExamples(), input.docStyleContext(),
                input.mdxComponentsContext(), input.assetGuidelines(), input.constraints());
    }

    private Optional<String> findDenyViolation(WriteMdxDocInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = denyList.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findSecretViolation(WriteMdxDocInput input) {
        for (String field : allTextFields(input)) {
            Optional<String> violation = secretScanner.findViolation(field);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findLimitViolation(WriteMdxDocInput input) {
        if (input.libraryContext().length() > limitsGuard.maxContextChars()) {
            return Optional.of("libraryContext exceeds maximum size of " + limitsGuard.maxContextChars()
                    + " characters");
        }
        for (String[] field : new String[][] {
                {"publicApi", input.publicApi()},
                {"configurationProperties", input.configurationProperties()},
                {"usageExamples", input.usageExamples()},
                {"docStyleContext", input.docStyleContext()},
                {"mdxComponentsContext", input.mdxComponentsContext()}}) {
            if (field[1].length() > limitsGuard.maxContextChars()) {
                return Optional.of(field[0] + " exceeds maximum size of "
                        + limitsGuard.maxContextChars() + " characters");
            }
        }
        LimitsGuard.OptionalLimit assetLimit = limitsGuard.checkContextSize(input.assetGuidelines());
        if (assetLimit.exceeded()) {
            return Optional.of(assetLimit.message());
        }
        LimitsGuard.OptionalLimit constraintsLimit = limitsGuard.checkConstraintsSize(input.constraints());
        if (constraintsLimit.exceeded()) {
            return Optional.of(constraintsLimit.message());
        }
        return Optional.empty();
    }

    private Map<String, Object> toPayload(WriteMdxDocOutput output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", output.status().name());
        payload.put("summary", output.summary());
        payload.put("frontMatter", output.frontMatter());
        payload.put("imports", output.imports());
        payload.put("mdxContent", output.mdxContent());
        payload.put("outline", output.outline());
        payload.put("examples", output.examples());
        payload.put("admonitions", output.admonitions());
        payload.put("assetsNeeded", output.assetsNeeded());
        payload.put("linksToAdd", output.linksToAdd());
        payload.put("claimsToVerify", output.claimsToVerify());
        payload.put("validationChecklist", output.validationChecklist());
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

    // ---- Defensive MDX-doc parsing ------------------------------------------------------------

    ParsedDoc parseDoc(WriteMdxDocInput input, String text) {
        Map<String, List<String>> sections = splitSections(text);

        String summary = buildSummary(input, sections.get("SUMMARY"));
        String frontMatter = joinSection(sections.get("FRONT_MATTER"));
        List<String> imports = bullets(sections.get("IMPORTS"));
        String mdxContent = joinSection(sections.get("MDX_CONTENT"));
        List<String> outline = bullets(sections.get("OUTLINE"));
        List<String> examples = bullets(sections.get("EXAMPLES"));
        List<String> admonitions = bullets(sections.get("ADMONITIONS"));
        List<String> assetsNeeded = bullets(sections.get("ASSETS_NEEDED"));
        List<String> linksToAdd = bullets(sections.get("LINKS_TO_ADD"));
        List<String> claimsToVerify = bullets(sections.get("CLAIMS_TO_VERIFY"));
        List<String> validationChecklist = bullets(sections.get("VALIDATION_CHECKLIST"));
        List<String> risks = bullets(sections.get("RISKS"));
        List<String> safetyNotes = bullets(sections.get("SAFETY_NOTES"));
        List<String> assumptions = bullets(sections.get("ASSUMPTIONS"));

        if (mdxContent.isBlank() && frontMatter.isBlank() && outline.isEmpty()
                && imports.isEmpty() && examples.isEmpty()) {
            // Non-compliant response: keep the whole (truncated) text usable in mdxContent.
            mdxContent = text == null ? "" : text.strip();
        }
        return new ParsedDoc(summary, frontMatter, imports, mdxContent, outline, examples,
                admonitions, assetsNeeded, linksToAdd, claimsToVerify, validationChecklist, risks,
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

    private String buildSummary(WriteMdxDocInput input, List<String> summaryLines) {
        if (summaryLines != null) {
            for (String line : summaryLines) {
                String candidate = sanitizeLine(line);
                if (candidate != null) {
                    return cap(candidate);
                }
            }
        }
        return cap("MDX " + input.docType().wireValue() + " draft (" + input.outputFormat().wireValue()
                + ") for " + input.targetAudience().wireValue() + ": " + input.docSubject());
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

    record ParsedDoc(
            String summary,
            String frontMatter,
            List<String> imports,
            String mdxContent,
            List<String> outline,
            List<String> examples,
            List<String> admonitions,
            List<String> assetsNeeded,
            List<String> linksToAdd,
            List<String> claimsToVerify,
            List<String> validationChecklist,
            List<String> risks,
            List<String> safetyNotes,
            List<String> assumptions) {
    }
}
